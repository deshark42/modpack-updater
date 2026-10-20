package io.github.deshark42.modpackupdater.s3;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.json.Json;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The blobs a workspace has already confirmed in the bucket, kept in one local append-only JSON
 * Lines file.
 *
 * <p>Only confirmed objects are written: a hash in the cache may be skipped without asking the
 * store, and a hash missing from it is merely unknown, never absent. The cache can be deleted at
 * any time; the next publish then asks the store about every blob again. It never takes part in
 * what a release contains.
 *
 * <p>The first line is a header object tying the file to one bucket location and recording when it
 * was created; every later line is one {@code {"hash": ...}} object. A different endpoint, bucket
 * or prefix, an unreadable or malformed file, or a cache older than {@link #MAX_AGE} discards it as
 * a whole and starts a new one.
 */
public final class BlobCache {

    /** The hash of a sha256 content address, as the manifest carries it. */
    private static final int HASH_LENGTH = 64;
    private static final String VERSION = "v1";
    private static final Duration MAX_AGE = Duration.ofDays(30);

    private final Path file;
    private final String fingerprint;
    private final Clock clock;
    private final Set<String> known;
    private boolean reset;

    private BlobCache(Path file, String fingerprint, Clock clock, Set<String> known, boolean reset) {
        this.file = file;
        this.fingerprint = fingerprint;
        this.clock = clock;
        this.known = known;
        this.reset = reset;
    }

    /** Loads the cache for {@code config}'s bucket location, starting empty if it is unusable. */
    public static BlobCache open(Path workspace, Config config) {
        return open(workspace, config, Clock.systemUTC());
    }

    static BlobCache open(Path workspace, Config config, Clock clock) {
        Path file = Layout.knownBlobsFile(workspace);
        String fingerprint = fingerprint(config);
        Read read = read(file);
        long now = clock.instant().getEpochSecond();
        boolean usable = read != null
                && read.fingerprint().equals(fingerprint)
                && now - read.createdAt() <= MAX_AGE.getSeconds();
        Set<String> known = ConcurrentHashMap.newKeySet();
        if (usable) {
            known.addAll(read.hashes());
        }
        return new BlobCache(file, fingerprint, clock, known, !usable);
    }

    /**
     * Whether {@code hash} is in the bucket, answering from the cache and asking the store only on
     * a miss. A confirmed object is written to the cache before this returns.
     */
    public boolean exists(S3Store store, String hash) {
        if (known.contains(hash)) {
            return true;
        }
        if (!store.exists(ObjectKeys.blob(hash))) {
            return false;
        }
        remember(hash);
        return true;
    }

    /**
     * Records a blob that is now known to be in the bucket. The append is one atomic operation, so
     * a crash never leaves a half-written entry and concurrent publishers never overwrite each
     * other's entries.
     */
    public synchronized void remember(String hash) {
        if (!known.add(hash)) {
            return;
        }
        try {
            if (reset) {
                AtomicFile.write(file, headerLine(fingerprint, clock.instant().getEpochSecond()));
                reset = false;
            }
            append(hash);
        } catch (RuntimeException e) {
            known.remove(hash);
            throw e;
        } catch (IOException e) {
            known.remove(hash);
            throw new UserError("cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    /** For tests and diagnostics: whether the cache holds {@code hash} without asking the store. */
    boolean known(String hash) {
        return known.contains(hash);
    }

    private void append(String hash) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] line = entryLine(hash).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             FileLock ignored = channel.lock()) {
            ByteBuffer buffer = ByteBuffer.wrap(line);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }
    }

    private static Read read(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        if (lines.isEmpty()) {
            return null;
        }
        Map<String, Object> header = object(lines.get(0));
        if (header == null || !VERSION.equals(header.get("version"))) {
            return null;
        }
        if (!(header.get("fingerprint") instanceof String fingerprint)) {
            return null;
        }
        if (!(header.get("createdAt") instanceof Long createdAt)) {
            return null;
        }
        Set<String> hashes = ConcurrentHashMap.newKeySet();
        for (int i = 1; i < lines.size(); i++) {
            Map<String, Object> entry = object(lines.get(i));
            if (entry == null || !(entry.get("hash") instanceof String hash) || !isHash(hash)) {
                return null;
            }
            hashes.add(hash);
        }
        return new Read(fingerprint, createdAt, hashes);
    }

    private static Map<String, Object> object(String line) {
        try {
            return Json.asObject(Json.parse(line), "known-blobs line");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String headerLine(String fingerprint, long createdAt) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("version", VERSION);
        header.put("fingerprint", fingerprint);
        header.put("createdAt", createdAt);
        return Json.write(header);
    }

    private static String entryLine(String hash) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("hash", hash);
        return Json.write(entry);
    }

    /** The bucket location the cache belongs to: a cache from one location is not used for another. */
    static String fingerprint(Config config) {
        String identity = config.endpoint() + "\n" + config.bucket() + "\n" + config.prefix();
        return Sha256.hex(sha256(identity.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static boolean isHash(String value) {
        if (value.length() != HASH_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private record Read(String fingerprint, long createdAt, Set<String> hashes) {
    }
}

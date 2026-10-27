package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AppliedRelease;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import io.github.deshark42.modpackupdater.fs.LocalContent;
import io.github.deshark42.modpackupdater.fs.LocalContent.Local;
import io.github.deshark42.modpackupdater.fs.LocalContent.Reuse;
import io.github.deshark42.modpackupdater.fs.PathGuard;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.fs.Sizes;
import io.github.deshark42.modpackupdater.log.Logging;
import io.github.deshark42.modpackupdater.log.Text;
import io.github.deshark42.modpackupdater.model.ChunkTable;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.FileEntry;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.Names;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import io.github.deshark42.modpackupdater.model.Plan;
import io.github.deshark42.modpackupdater.model.Pointer;
import io.github.deshark42.modpackupdater.model.ReleaseIdentity;
import io.github.deshark42.modpackupdater.model.SafePath;
import io.github.deshark42.modpackupdater.s3.ObjectReader;
import io.github.deshark42.modpackupdater.s3.S3ReadStore;
import io.github.deshark42.modpackupdater.s3.S3Store;
import io.github.deshark42.modpackupdater.security.ReleaseSigning;
import io.github.deshark42.modpackupdater.security.UpdaterProtection;
import io.github.deshark42.modpackupdater.util.Parallel;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Update one tag to the release the bucket says is latest, or to the one named on the command line.
 *
 * <p>The run has two phases. The plan is derived from the two manifests alone: it decides which
 * paths change and never looks at the files on disk, so a path both releases agree on is left alone
 * even when the player edited it. The download phase then looks at the target paths: one that
 * already holds the target content costs nothing, one that holds other content is split for reuse
 * before it is moved aside, and a path with nothing on disk has to be fetched whole.
 *
 * <p>The chunk tables say how each target file is split; splitting the target path's current file,
 * the files of the paths this run deletes, and the copies an earlier run left in
 * {@code backup/<target release>/} with the same parameters finds the chunks already on disk, so a
 * changed file only fetches the chunks around the change. A copy that is missing or unreadable
 * simply contributes no chunks, and the file is fetched whole.
 *
 * <p>The console shows the milestones of the run; the transcript keeps the per-file detail as well.
 */
public final class Update {

    private static final Logger LOG = LoggerFactory.getLogger(Update.class);

    private Update() {
    }

    public static void run(Path workspace, String tag, String releaseId) {
        run(workspace, tag, releaseId, Clock.systemUTC());
    }

    public static void run(Path workspace, String tag, String releaseId, Clock clock) {
        run(workspace, tag, releaseId, UpdaterProtection.runningUpdater(), clock);
    }

    static void run(Path workspace, String tag, String releaseId, Path updater) {
        run(workspace, tag, releaseId, updater, Clock.systemUTC());
    }

    private static void run(Path workspace, String tag, String releaseId, Path updater, Clock clock) {
        Names.requireTag(tag);
        if (releaseId != null) {
            Names.requireReleaseId(releaseId);
        }
        long startedAt = System.nanoTime();
        LOG.debug("tag {}: loading workspace configuration", tag);
        Config config = Workspace.load(workspace);
        LOG.debug("tag {}: validating signing key paths", tag);
        config.privateKeyFile(workspace); // Validate its location without requiring a subscriber to hold a private key.
        Path publicKeyFile = config.publicKeyFile(workspace);
        LOG.debug("tag {}: loading trusted public key", tag);
        PublicKey trustedKey = ReleaseSigning.publicKey(publicKeyFile);
        LOG.debug("tag {}: locating updater and workspace", tag);
        UpdaterProtection protection = new UpdaterProtection(workspace, updater);
        LOG.debug("tag {}: initializing object store", tag);
        ObjectReader store = config.readEndpoint() == null ? new S3Store(config, clock) : new S3ReadStore(config);

        // an explicit release id bypasses latest.json entirely, so a pinned update works even while
        // the pointer is missing or names a release the caller does not want
        String targetId = releaseId;
        if (targetId == null) {
            LOG.debug("tag {}: resolving latest release", tag);
            String latestKey = ObjectKeys.latest(tag);
            Pointer latest = Pointer.parse(new String(store.getBytes(latestKey), StandardCharsets.UTF_8),
                    store.key(latestKey));
            targetId = latest.releaseId();
        }

        // Both manifest preflights share read-only metadata until payload writes begin.
        PathGuard.Preflight preflight = PathGuard.preflight();
        AppliedRelease state = AppliedRelease.read(workspace, tag, targetId);
        String applied = state.releaseId();
        Manifest local = state.manifest();
        Path currentFile = Layout.currentFile(workspace, tag);
        if (targetId.equals(applied)) {
            LOG.info("tag {} is already at release {}", tag, applied);
            return;
        }
        if (local != null) {
            LOG.debug("tag {}: checking {} local manifest paths for release {}", tag, local.files().size(), applied);
            protection.require(local, config.concurrency(), preflight);
        }
        LOG.info("updating tag {} to release {}", tag, targetId);

        LOG.debug("tag {}: fetching target manifest for release {}", tag, targetId);
        String targetKey = ObjectKeys.release(tag, targetId);
        byte[] manifestBytes = store.getBytes(targetKey);
        LOG.debug("tag {}: fetching target signature for release {}", tag, targetId);
        byte[] signature = store.getBytes(ObjectKeys.signature(tag, targetId));
        LOG.debug("tag {}: verifying target signature for release {}", tag, targetId);
        ReleaseSigning.verify(manifestBytes, signature, trustedKey);
        LOG.debug("tag {}: parsing and validating target manifest for release {}", tag, targetId);
        Manifest target = Manifest.parse(new String(manifestBytes, StandardCharsets.UTF_8), store.key(targetKey));
        ReleaseIdentity.require(target, tag, targetId);
        LOG.debug("tag {}: checking {} target manifest paths for release {}", tag, target.files().size(), targetId);
        protection.require(target, config.concurrency(), preflight);
        LOG.info("verified the release signature for tag {} release {}", tag, targetId);
        LOG.debug("tag {}: building update plan", tag);
        Path releaseFile = Layout.releaseFile(workspace, tag, target.releaseId());
        Plan plan = Plan.between(local, target);
        if (applied == null) {
            LOG.info("tag {} has no release applied yet, so {} is the first one", tag, target.releaseId());
        } else {
            LOG.info("tag {} moves from release {} to {}", tag, applied, target.releaseId());
        }
        LOG.info("{} to apply: {}", Text.count(plan.size(), "file"), plan.describe());
        for (String path : plan.added()) {
            LOG.debug("added {}", path);
        }
        for (String path : plan.replaced()) {
            LOG.debug("replaced {}", path);
        }
        for (String path : plan.deleted()) {
            LOG.debug("deleted {}", path);
        }

        // files moved out of the way are named after the release they came from; a first update has
        // no such release, so it gets a directory no release id can name
        LOG.debug("tag {}: validating affected paths and backups", tag);
        Path backup = applied == null
                ? Layout.initialBackupDir(workspace, tag)
                : Layout.backupDir(workspace, tag, applied);
        List<String> downloads = plan.downloads();
        // Both manifest preflights already validated every source path this plan touches, so only the
        // backup destinations are left. A late one must not fail after earlier files have moved.
        for (String path : downloads) {
            SafePath.resolve(backup, path);
        }
        for (String path : plan.deleted()) {
            SafePath.resolve(backup, path);
        }

        int moved = 0;
        int fetchedFiles = 0;
        long downloadedBytes = 0;
        long downloadNanos = 0;
        int reused = 0;
        int reusedFiles = 0;
        LOG.debug("tag {}: checking local target content", tag);
        Path blobDir = Layout.blobTempDir(workspace);
        Path fileDir = Layout.fileTempDir(workspace);
        try {
            // The plan says what may have to change; the target paths say what still does. Hashing
            // happens before any chunk table is fetched, so a file that already matches the target
            // costs no request beyond the two manifests. It is also the longest stretch with no
            // other output, so it gets the same bar the transfers use.
            Set<Path> deletions = downloads.isEmpty() ? Set.of() : LocalContent.deletionPaths(workspace, plan.deleted());
            Set<String> skipped = ConcurrentHashMap.newKeySet();
            int toCheck = downloads.size();
            AtomicInteger checked = new AtomicInteger();
            Logging.progress("check", 0, toCheck, "file");
            Parallel.forEach(downloads, config.concurrency(), path -> {
                if (LocalContent.matchesTarget(workspace, path, target.files().get(path), deletions)) {
                    skipped.add(path);
                }
                Logging.progress("check", checked.incrementAndGet(), toCheck, "file");
            });
            List<String> toFetch = new ArrayList<>();
            for (String path : downloads) {
                if (!skipped.contains(path)) {
                    toFetch.add(path);
                }
            }
            fetchedFiles = toFetch.size();
            if (!skipped.isEmpty()) {
                LOG.info("skipped {}: already at the target content", Text.count(skipped.size(), "file"));
            }

            Map<String, Path> assembled = Map.of();
            if (!toFetch.isEmpty()) {
                long neededBytes = 0;
                for (String path : toFetch) {
                    neededBytes += target.files().get(path).size();
                }
                LOG.debug("fetching {} ({})", Text.count(toFetch.size(), "file"),
                        Sizes.human(neededBytes));
                LOG.debug("tag {}: preparing staging directories", tag);
                deleteTree(workspace, blobDir);
                deleteTree(workspace, fileDir);
                try {
                    LOG.debug("tag {}: fetching chunk tables", tag);
                    Map<String, ChunkTable> tables = chunkTables(store, target, toFetch, config.concurrency());
                    LOG.debug("tag {}: finding reusable local chunks", tag);
                    Reuse reuse = LocalContent.localChunks(workspace, backup, plan, toFetch, tag, targetId, target, tables,
                            config.concurrency());
                    LOG.debug("tag {}: planning missing chunks", tag);
                    Map<String, Local> existing = reuse.chunks();
                    Set<String> needed = new LinkedHashSet<>();
                    for (String path : toFetch) {
                        needed.addAll(tables.get(target.files().get(path).indexId()).chunks());
                    }
                    List<String> missing = new ArrayList<>();
                    for (String hash : needed) {
                        if (!existing.containsKey(hash)) {
                            missing.add(hash);
                        }
                    }
                    reused = needed.size() - missing.size();
                    reusedFiles = reuse.files();
                    if (reused > 0) {
                        LOG.debug("reused {} from {} already on disk", Text.count(reused, "chunk"),
                                Text.count(reusedFiles, "file"));
                    }
                    if (!missing.isEmpty()) {
                        LOG.debug("tag {}: downloading missing chunks", tag);
                        Files.createDirectories(blobDir);
                        Download download = download(store, missing, blobDir, config.concurrency());
                        downloadedBytes = download.bytes();
                        downloadNanos = download.nanos();
                    }
                    LOG.debug("tag {}: assembling target files", tag);
                    assembled = assembleAll(toFetch, target, tables, existing, blobDir, fileDir,
                            config.concurrency());
                } catch (IOException e) {
                    throw new UserError("cannot stage files under " + Layout.tempDir(workspace) + ": "
                            + e.getMessage(), e);
                }
            }

            // Deletions go first, so a path that becomes a file can replace a directory that shrank
            // out of the manifest. A target that already exists is moved aside before its new
            // content goes in, which is what makes the old file recoverable.
            LOG.debug("tag {}: applying files and moving backups", tag);
            for (String path : plan.deleted()) {
                moved += moveAside(workspace, backup, path) ? 1 : 0;
            }
            for (String path : toFetch) {
                Path targetPath = SafePath.resolve(workspace, path);
                if (exists(targetPath)) {
                    moved += moveAside(workspace, backup, path) ? 1 : 0;
                }
                Path staged = assembled.get(path);
                if (staged == null) {
                    throw new IllegalStateException("no assembled copy of " + path);
                }
                Path parent = targetPath.getParent();
                try {
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    AtomicFile.move(staged, targetPath);
                } catch (IOException e) {
                    throw new UserError("cannot write " + targetPath + ": " + e.getMessage(), e);
                }
            }
            if (moved > 0) {
                LOG.info("moved {} aside to {}", Text.count(moved, "file"),
                        Text.relativeTo(workspace, backup));
            }
        } finally {
            LOG.debug("tag {}: cleaning staging directories", tag);
            deleteTree(workspace, blobDir);
            deleteTree(workspace, fileDir);
        }

        LOG.debug("tag {}: writing local release state", tag);
        target.write(releaseFile);
        new Pointer(target.releaseId()).write(currentFile);
        LOG.info("wrote {}", Text.relativeTo(workspace, releaseFile));
        LOG.info("wrote {}", Text.relativeTo(workspace, currentFile));
        LOG.info(transferSummary(fetchedFiles, downloadedBytes, downloadNanos, reused));
        LOG.info("updated tag {} to release {} in {}", tag, target.releaseId(),
                Text.elapsed(System.nanoTime() - startedAt));
    }

    /** The line before the closing one: how much data this run moved, and how much it reused. */
    private static String transferSummary(int files, long bytes, long nanos, int reused) {
        StringBuilder text = new StringBuilder();
        if (bytes == 0) {
            text.append("downloaded nothing");
        } else {
            text.append("downloaded ").append(Text.count(files, "file")).append(", ")
                    .append(Sizes.human(bytes)).append(" in ").append(Text.duration(nanos));
        }
        if (reused > 0) {
            text.append("; reused ").append(Text.count(reused, "chunk")).append(" from local copies");
        }
        return text.toString();
    }

    /**
     * The chunk table of every file that has to be assembled, asked for once per distinct table and
     * checked against the manifest so a misaddressed object cannot be split into a wrong file.
     */
    private static Map<String, ChunkTable> chunkTables(ObjectReader store, Manifest target, List<String> downloads,
            int concurrency) {
        Set<String> ids = new TreeSet<>();
        for (String path : downloads) {
            ids.add(target.files().get(path).indexId());
        }
        Map<String, ChunkTable> tables = new ConcurrentHashMap<>();
        Parallel.forEach(ids, concurrency, id -> {
            String key = ObjectKeys.index(id);
            ChunkTable table = ChunkTable.parse(new String(store.getBytes(key), StandardCharsets.UTF_8),
                    store.key(key));
            if (!table.indexId().equals(id)) {
                throw new UserError(store.key(key) + " is not the chunk table it is named after");
            }
            tables.put(id, table);
        });
        for (String path : downloads) {
            FileEntry entry = target.files().get(path);
            ChunkTable table = tables.get(entry.indexId());
            if (table.size() != entry.size()) {
                throw new UserError("the chunk table of " + path + " covers " + table.size()
                        + " bytes, the manifest says " + entry.size());
            }
        }
        return tables;
    }

    /** What the wire part of the run cost: the bytes fetched and how long fetching them took. */
    private record Download(long bytes, long nanos) {
    }

    /** Fetches the missing chunks into the run's blob directory, one file per chunk. */
    private static Download download(ObjectReader store, List<String> missing, Path tempDir, int concurrency) {
        int total = missing.size();
        AtomicInteger done = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        long startedAt = System.nanoTime();
        Parallel.forEach(missing, concurrency, hash -> {
            Path file = tempDir.resolve(hash);
            store.download(ObjectKeys.blob(hash), file);
            long length;
            try {
                length = Files.size(file);
            } catch (IOException e) {
                throw new UserError("cannot read " + file + ": " + e.getMessage(), e);
            }
            Logging.progress("download", done.incrementAndGet(), total, bytes.addAndGet(length), "chunk");
        });
        long nanos = System.nanoTime() - startedAt;
        LOG.debug("downloaded {} ({}) in {} ({})", Text.count(done.get(), "chunk"),
                Sizes.human(bytes.get()), Text.duration(nanos), Sizes.rate(bytes.get(), nanos));
        return new Download(bytes.get(), nanos);
    }

    /** Writes every fetched file into the run's file directory, each one on its own. */
    private static Map<String, Path> assembleAll(List<String> downloads, Manifest target,
            Map<String, ChunkTable> tables, Map<String, Local> existing, Path blobDir, Path fileDir,
            int concurrency) {
        Map<String, Path> assembled = new ConcurrentHashMap<>();
        Parallel.forEach(downloads, concurrency, path -> {
            FileEntry entry = target.files().get(path);
            assembled.put(path, assemble(path, entry, tables.get(entry.indexId()), existing, blobDir, fileDir));
        });
        return assembled;
    }

    /**
     * Writes one file from its chunks in table order into the run's file directory, hashing as it
     * goes. The staged file only counts once the whole-file hash matches the manifest, so a failed
     * assembly never replaces anything on the target path.
     */
    private static Path assemble(String path, FileEntry entry, ChunkTable table,
            Map<String, Local> existing, Path blobDir, Path fileDir) {
        Path temp = null;
        Path staged = null;
        Map<Path, FileChannel> sources = new HashMap<>();
        try {
            Files.createDirectories(fileDir);
            temp = Files.createTempFile(fileDir, "file-", ".part");
            MessageDigest digest = digest();
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                for (String hash : table.chunks()) {
                    Local local = existing.get(hash);
                    byte[] bytes = local == null
                            ? Files.readAllBytes(blobDir.resolve(hash))
                            : read(local, sources);
                    out.write(bytes);
                    digest.update(bytes);
                }
            }
            String assembled = Sha256.hex(digest.digest());
            if (!assembled.equals(entry.hash())) {
                throw new UserError("the chunks under " + path + " assemble to a different file than the"
                        + " manifest records (" + assembled + " instead of " + entry.hash() + ")");
            }
            staged = temp;
            return staged;
        } catch (IOException e) {
            throw new UserError("cannot assemble " + path + ": " + e.getMessage(), e);
        } finally {
            for (FileChannel channel : sources.values()) {
                closeQuietly(channel);
            }
            if (staged == null) {
                deleteQuietly(temp);
            }
        }
    }

    /** Reads one cut back from the copy it was found in, reusing one channel per source file. */
    private static byte[] read(Local local, Map<Path, FileChannel> sources) throws IOException {
        FileChannel channel = sources.get(local.file());
        if (channel == null) {
            channel = FileChannel.open(local.file(), StandardOpenOption.READ);
            sources.put(local.file(), channel);
        }
        byte[] bytes = new byte[local.cut().length()];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        channel.position(local.cut().offset());
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IOException(local.file() + " ends before chunk " + local.cut().hash() + " does");
            }
        }
        return bytes;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean moveAside(Path workspace, Path backup, String path) {
        Path source = SafePath.resolve(workspace, path);
        if (!exists(source)) {
            return false;
        }
        Path target = SafePath.resolve(backup, path);
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            AtomicFile.move(source, target);
        } catch (IOException e) {
            throw new UserError("cannot move " + source + " to " + target + ": " + e.getMessage(), e);
        }
        return true;
    }

    private static void deleteTree(Path workspace, Path dir) {
        PathGuard.require(workspace, dir);
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path path : walk.peek(file -> PathGuard.require(workspace, file))
                    .sorted(Comparator.reverseOrder()).toList()) {
                PathGuard.require(workspace, path);
                deleteQuietly(path);
            }
        } catch (IOException e) {
            // the run is already ending; a leftover temporary directory is the least of it
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // best effort: the run's own failure is what gets reported
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            // best effort: the run's own failure is what gets reported
        }
    }
}

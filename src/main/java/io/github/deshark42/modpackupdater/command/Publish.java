package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Chunker;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.fs.Sizes;
import io.github.deshark42.modpackupdater.fs.Tree;
import io.github.deshark42.modpackupdater.log.Logging;
import io.github.deshark42.modpackupdater.log.Text;
import io.github.deshark42.modpackupdater.model.Chunking;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.FileEntry;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.Names;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import io.github.deshark42.modpackupdater.model.Pointer;
import io.github.deshark42.modpackupdater.model.RemoveRules;
import io.github.deshark42.modpackupdater.model.SafePath;
import io.github.deshark42.modpackupdater.s3.BlobCache;
import io.github.deshark42.modpackupdater.s3.S3Store;
import io.github.deshark42.modpackupdater.security.ReleaseSigning;
import io.github.deshark42.modpackupdater.util.Parallel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publish one complete release of a tag: merge the tag's base tree with its overlay, drop what
 * remove.txt takes out of base, split the files into content-defined chunks, upload the chunks and
 * chunk tables the bucket does not have yet, then write the release manifest, its Ed25519 signature,
 * and finally the latest pointer.
 *
 * <p>The console shows the milestones of the run; the transcript keeps the per-file detail as well.
 */
public final class Publish {

    /** How long the local blob cache stays usable unless the publish command says otherwise. */
    public static final int DEFAULT_CACHE_MAX_AGE_DAYS = 30;

    private static final Logger LOG = LoggerFactory.getLogger(Publish.class);

    private Publish() {
    }

    public static void run(Path workspace, String tag, String releaseId) {
        run(workspace, tag, releaseId, DEFAULT_CACHE_MAX_AGE_DAYS);
    }

    public static void run(Path workspace, String tag, String releaseId, int cacheMaxAgeDays) {
        Names.requireTag(tag);
        Names.requireReleaseId(releaseId);
        if (cacheMaxAgeDays < 1) {
            throw new UserError("cache max age must be at least 1 day: " + cacheMaxAgeDays);
        }
        long startedAt = System.nanoTime();
        LOG.info("publishing release {} of tag {}", releaseId, tag);
        Path base = Layout.baseDir(workspace, tag);
        Path overlay = Layout.overlayDir(workspace, tag);
        String baseName = Text.relativeTo(workspace, base);
        Map<String, Long> baseFiles = Tree.list(base);
        LOG.info("scanned {}: {}, {}", baseName, Text.count(baseFiles.size(), "file"),
                Sizes.human(total(baseFiles)));
        boolean hasOverlay = Files.isDirectory(overlay);
        Map<String, Long> overlayFiles = hasOverlay ? Tree.list(overlay) : Map.of();
        if (hasOverlay) {
            LOG.info("scanned {}: {}, {}", Text.relativeTo(workspace, overlay),
                    Text.count(overlayFiles.size(), "file"), Sizes.human(total(overlayFiles)));
        }
        String removeName = Text.relativeTo(workspace, Layout.removeFile(workspace, tag));
        RemoveRules rules = RemoveRules.read(Layout.removeFile(workspace, tag));
        RemoveRules.Outcome outcome = rules.apply(baseFiles.keySet());
        for (String removed : outcome.removed()) {
            LOG.debug("dropped {} ({}) from {} by the rules in {}", removed,
                    Sizes.human(baseFiles.get(removed)), baseName, removeName);
        }
        if (rules.size() > 0) {
            LOG.info("removed {} from {} by the {} in {}", Text.count(outcome.removed().size(), "file"),
                    baseName, Text.count(rules.size(), "rule"), removeName);
        }
        if (!outcome.unmatched().isEmpty()) {
            LOG.warn("{} in {} matched nothing: {}",
                    Text.count(outcome.unmatched().size(), "rule"), removeName,
                    String.join(", ", outcome.unmatched()));
        }
        Map<String, Long> files = new TreeMap<>(baseFiles);
        files.keySet().removeAll(outcome.removed());
        Set<String> overlaid = new LinkedHashSet<>();
        int added = 0;
        int replaced = 0;
        for (Map.Entry<String, Long> entry : overlayFiles.entrySet()) {
            if (files.put(entry.getKey(), entry.getValue()) == null) {
                added++;
                LOG.debug("added {} ({}) from {}", entry.getKey(),
                        Sizes.human(entry.getValue()), Text.relativeTo(workspace, overlay));
            } else {
                replaced++;
                LOG.debug("replaced {} ({}) with the copy from {}", entry.getKey(),
                        Sizes.human(entry.getValue()), Text.relativeTo(workspace, overlay));
            }
            overlaid.add(entry.getKey());
        }
        if (hasOverlay && added + replaced > 0) {
            LOG.info("merged {} into {}: {} added, {} replaced",
                    Text.relativeTo(workspace, overlay), baseName, Text.count(added, "file"),
                    Text.count(replaced, "file"));
        }
        // Reject reserved paths before any content or metadata can reach the bucket.
        files.keySet().forEach(SafePath::require);
        String releaseKey = ObjectKeys.release(tag, releaseId);
        String latestKey = ObjectKeys.latest(tag);

        Config config = Workspace.load(workspace);
        PrivateKey signingKey = ReleaseSigning.privateKey(config.privateKeyFile(workspace));
        S3Store store = new S3Store(config);
        if (store.exists(releaseKey)) {
            throw new UserError("release " + releaseId + " of tag " + tag
                    + " already exists; a published release is never rewritten");
        }
        BlobCache cache = BlobCache.open(workspace, config, Duration.ofDays(cacheMaxAgeDays));

        // Splitting every file also hashes it, so the manifest is complete before anything moves.
        Map<String, FileEntry> entries = new ConcurrentHashMap<>();
        Map<String, Placement> placements = new ConcurrentHashMap<>();
        Map<String, byte[]> tables = new ConcurrentHashMap<>();
        Parallel.forEach(files.keySet(), config.concurrency(), path -> {
            Path root = overlaid.contains(path) ? overlay : base;
            Path file = SafePath.resolve(root, path);
            Chunker.Result split = Chunker.split(file, Chunking.DEFAULT,
                    cut -> placements.putIfAbsent(cut.hash(), new Placement(file, path, cut)));
            entries.put(path, new FileEntry(split.hash(), split.table().size(), split.table().indexId()));
            tables.putIfAbsent(split.table().indexId(), split.table().bytes());
        });
        LOG.info("split {} into {}", Text.count(entries.size(), "file"),
                Text.count(placements.size(), "chunk"));

        Manifest manifest = new Manifest(tag, releaseId, Instant.now().truncatedTo(ChronoUnit.SECONDS),
                Manifest.SHA256, new TreeMap<>(entries));
        byte[] manifestBytes = manifest.toJson().getBytes(StandardCharsets.UTF_8);
        byte[] signature = ReleaseSigning.sign(manifestBytes, signingKey);

        // Ask which chunks and chunk tables are missing before uploading, so progress can count
        // them. Objects the cache already knows cost no request at all.
        int objectsToCheck = placements.size() + tables.size();
        AtomicInteger checked = new AtomicInteger();
        Logging.progress("check", 0, objectsToCheck, "object");
        Map<String, Placement> pendingChunks = new ConcurrentHashMap<>();
        Parallel.forEach(placements.entrySet(), config.concurrency(), chunk -> {
            if (!cache.chunkExists(store, chunk.getKey())) {
                pendingChunks.put(chunk.getKey(), chunk.getValue());
            }
            Logging.progress("check", checked.incrementAndGet(), objectsToCheck, "object");
        });
        Map<String, byte[]> pendingTables = new ConcurrentHashMap<>();
        Parallel.forEach(tables.entrySet(), config.concurrency(), table -> {
            if (!cache.indexExists(store, table.getKey())) {
                pendingTables.put(table.getKey(), table.getValue());
            }
            Logging.progress("check", checked.incrementAndGet(), objectsToCheck, "object");
        });

        int toUpload = pendingChunks.size();
        int skipped = placements.size() - toUpload;
        long pendingBytes = 0;
        for (Placement placement : pendingChunks.values()) {
            pendingBytes += placement.cut().length();
        }
        if (toUpload > 0) {
            LOG.debug("the chunks to upload add up to {}", Sizes.human(pendingBytes));
        }
        LOG.info("planned the upload: {} to upload, {} already in the bucket",
                Text.count(toUpload, "chunk"), Text.count(skipped, "chunk"));
        Set<String> uploading = new TreeSet<>();
        for (Placement placement : pendingChunks.values()) {
            uploading.add(placement.path());
        }
        for (String path : uploading) {
            LOG.debug("uploading {}", path);
        }
        AtomicInteger uploaded = new AtomicInteger();
        AtomicLong uploadedBytes = new AtomicLong();
        long uploadStartedAt = System.nanoTime();
        Parallel.forEach(pendingChunks.entrySet(), config.concurrency(), chunk -> {
            Placement placement = chunk.getValue();
            byte[] bytes = Chunker.read(placement.file(), placement.cut());
            if (!Sha256.ofBytes(bytes).equals(chunk.getKey())) {
                throw new UserError(placement.path() + " changed while it was being published");
            }
            store.putBytes(ObjectKeys.blob(chunk.getKey()), bytes);
            cache.remember(chunk.getKey());
            int done = uploaded.incrementAndGet();
            Logging.progress("upload", done, toUpload, uploadedBytes.addAndGet(bytes.length), "chunk");
        });
        long uploadNanos = System.nanoTime() - uploadStartedAt;
        if (toUpload > 0) {
            LOG.debug("uploaded {} ({}) in {} ({})", Text.count(uploaded.get(), "chunk"),
                    Sizes.human(uploadedBytes.get()), Text.duration(uploadNanos),
                    Sizes.rate(uploadedBytes.get(), uploadNanos));
        }
        if (!pendingTables.isEmpty()) {
            long tableBytes = 0;
            for (byte[] table : pendingTables.values()) {
                tableBytes += table.length;
            }
            long tableStartedAt = System.nanoTime();
            Parallel.forEach(pendingTables.entrySet(), config.concurrency(), table -> {
                store.putBytes(ObjectKeys.index(table.getKey()), table.getValue());
                cache.remember(table.getKey());
            });
            long tableNanos = System.nanoTime() - tableStartedAt;
            LOG.debug("uploaded {} ({}) in {} ({})", Text.count(pendingTables.size(), "chunk table"),
                    Sizes.human(tableBytes), Text.duration(tableNanos),
                    Sizes.rate(tableBytes, tableNanos));
        }

        int compressed = store.putGzip(releaseKey, manifestBytes);
        LOG.info("wrote {}: {} -> {}", releaseKey, Sizes.human(manifestBytes.length), Sizes.human(compressed));
        String signatureKey = ObjectKeys.signature(tag, releaseId);
        store.putBytes(signatureKey, signature);
        LOG.info("wrote {}: {}", signatureKey, Sizes.human(signature.length));
        LOG.info("wrote {}: {}", latestKey, write(store, latestKey, new Pointer(releaseId).toJson()));
        LOG.info(uploadedSummary(uploading.size(), skipped, uploadedBytes.get(), uploadNanos, toUpload));
        LOG.info("published release {} of tag {} as the latest in {}", releaseId, tag,
                Text.elapsed(System.nanoTime() - startedAt));
    }

    /** The line before the closing one: how much data this run moved, and how much it did not need. */
    private static String uploadedSummary(int files, int skipped, long bytes, long nanos, int toUpload) {
        if (toUpload == 0) {
            return skipped == 0
                    ? "uploaded nothing"
                    : "uploaded nothing: " + Text.count(skipped, "chunk") + " already in the bucket";
        }
        return "uploaded " + Text.count(files, "file") + ", " + Sizes.human(bytes) + " in "
                + Text.duration(nanos)
                + (skipped > 0 ? "; " + Text.count(skipped, "chunk") + " already in the bucket" : "");
    }

    private static long total(Map<String, Long> files) {
        long bytes = 0;
        for (long size : files.values()) {
            bytes += size;
        }
        return bytes;
    }

    private static String write(S3Store store, String key, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        store.putBytes(key, bytes);
        return Sizes.human(bytes.length);
    }

    /** Where one chunk's bytes can be read back from, and the path it belongs to in the release. */
    private record Placement(Path file, String path, Chunker.Cut cut) {
    }
}

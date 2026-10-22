package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Chunker;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.fs.Sizes;
import io.github.deshark42.modpackupdater.fs.Tree;
import io.github.deshark42.modpackupdater.log.Log;
import io.github.deshark42.modpackupdater.log.LogFile;
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
import io.github.deshark42.modpackupdater.util.Parallel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publish one complete release of a tag: merge the tag's base tree with its overlay, drop what
 * remove.txt takes out of base, split the files into content-defined chunks, upload the chunks and
 * chunk tables the bucket does not have yet, then write the release manifest and finally the latest
 * pointer.
 *
 * <p>The run is reported in three stages: what goes into the release, what moves to the bucket,
 * and what is written to record it.
 */
public final class Publish {

    /** How long the local blob cache stays usable unless the publish command says otherwise. */
    public static final int DEFAULT_CACHE_MAX_AGE_DAYS = 30;

    private Publish() {
    }

    public static void run(Path workspace, String tag, String releaseId, boolean dryRun, Log log) {
        run(workspace, tag, releaseId, DEFAULT_CACHE_MAX_AGE_DAYS, dryRun, log);
    }

    public static void run(Path workspace, String tag, String releaseId, int cacheMaxAgeDays,
            boolean dryRun, Log log) {
        Names.requireTag(tag);
        Names.requireReleaseId(releaseId);
        if (cacheMaxAgeDays < 1) {
            throw new UserError("cache max age must be at least 1 day: " + cacheMaxAgeDays);
        }
        log.headline("publish " + tag + " " + releaseId);
        log.phase("inputs");
        Path base = Layout.baseDir(workspace, tag);
        Path overlay = Layout.overlayDir(workspace, tag);
        Map<String, Long> baseFiles = Tree.list(base);
        log.step("scan", Log.relativeTo(workspace, base),
                Log.count(baseFiles.size(), "file") + ", " + Sizes.human(total(baseFiles)));
        boolean hasOverlay = Files.isDirectory(overlay);
        Map<String, Long> overlayFiles = hasOverlay ? Tree.list(overlay) : Map.of();
        if (hasOverlay) {
            log.step("scan", Log.relativeTo(workspace, overlay),
                    Log.count(overlayFiles.size(), "file") + ", " + Sizes.human(total(overlayFiles)));
        }
        RemoveRules rules = RemoveRules.read(Layout.removeFile(workspace, tag));
        RemoveRules.Outcome outcome = rules.apply(baseFiles.keySet());
        if (rules.size() > 0) {
            log.step("remove", Log.count(outcome.removed().size(), "file"),
                    "from " + Log.count(rules.size(), "rule"));
        }
        if (!outcome.unmatched().isEmpty()) {
            log.step("stale", Log.count(outcome.unmatched().size(), "rule"),
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
                log.item("add", entry.getKey());
            } else {
                replaced++;
                log.item("replace", entry.getKey());
            }
            overlaid.add(entry.getKey());
        }
        log.step("merge", Log.count(added, "file") + " added, " + Log.count(replaced, "file") + " replaced",
                Log.count(files.size(), "file") + ", " + Sizes.human(total(files)));
        String releaseKey = ObjectKeys.release(tag, releaseId);
        String latestKey = ObjectKeys.latest(tag);
        String contents = contents(added, replaced, rules, outcome, files);
        if (dryRun) {
            log.dryRun();
            log.summary("contents", contents);
            log.summary("release", releaseKey);
            log.summary("latest", latestKey + " would point at " + releaseId);
            return;
        }

        log.phase("transfer");
        Config config = Workspace.load(workspace, log);
        S3Store store = new S3Store(config);
        if (store.exists(releaseKey)) {
            throw new UserError("release " + releaseId + " of tag " + tag
                    + " already exists; a published release is never rewritten");
        }
        BlobCache cache = BlobCache.open(workspace, config, log, Duration.ofDays(cacheMaxAgeDays));

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
        log.step("chunk", Log.count(entries.size(), "file"), Log.count(placements.size(), "chunk"));

        // Ask which chunks and chunk tables are missing before uploading, so progress can count
        // them. Objects the cache already knows cost no request at all.
        Map<String, Placement> pendingChunks = new ConcurrentHashMap<>();
        Parallel.forEach(placements.entrySet(), config.concurrency(), chunk -> {
            if (!cache.chunkExists(store, chunk.getKey())) {
                pendingChunks.put(chunk.getKey(), chunk.getValue());
            }
        });
        Map<String, byte[]> pendingTables = new ConcurrentHashMap<>();
        Parallel.forEach(tables.entrySet(), config.concurrency(), table -> {
            if (!cache.indexExists(store, table.getKey())) {
                pendingTables.put(table.getKey(), table.getValue());
            }
        });

        int toUpload = pendingChunks.size();
        int skipped = placements.size() - toUpload;
        long pendingBytes = 0;
        for (Placement placement : pendingChunks.values()) {
            pendingBytes += placement.cut().length();
        }
        log.step("plan", Log.count(toUpload, "chunk") + " to upload, " + Log.count(skipped, "chunk") + " already there",
                toUpload > 0 ? Sizes.human(pendingBytes) + " to upload" : "");
        Set<String> uploading = new TreeSet<>();
        for (Placement placement : pendingChunks.values()) {
            uploading.add(placement.path());
        }
        for (String path : uploading) {
            log.item("upload", path);
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
            log.progress("upload", done, toUpload, uploadedBytes.addAndGet(bytes.length), "chunk");
        });
        long uploadNanos = System.nanoTime() - uploadStartedAt;
        if (toUpload > 0) {
            log.step("upload", Log.count(uploaded.get(), "chunk"),
                    Sizes.human(uploadedBytes.get()) + " in " + Log.duration(uploadNanos)
                            + " (" + Sizes.rate(uploadedBytes.get(), uploadNanos) + ")");
        }
        if (skipped > 0) {
            log.step("skip", Log.count(skipped, "chunk"), "already in the bucket");
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
            log.step("write", Log.count(pendingTables.size(), "chunk table"),
                    Sizes.human(tableBytes) + " in " + Log.duration(tableNanos)
                            + " (" + Sizes.rate(tableBytes, tableNanos) + ")");
        }

        Manifest manifest = new Manifest(tag, releaseId, Instant.now().truncatedTo(ChronoUnit.SECONDS),
                Manifest.SHA256, new TreeMap<>(entries));
        log.phase("commit");
        log.step("write", releaseKey, write(store, releaseKey, manifest.toJson()));
        log.step("write", latestKey, write(store, latestKey, new Pointer(releaseId).toJson()));
        log.done(tag + " " + releaseId + " is now the latest");
        log.summary("contents", contents);
        log.summary("uploaded", uploaded(toUpload, uploaded.get(), skipped, uploadedBytes.get(), uploadNanos));
        log.summary("release", releaseKey);
        log.summary("latest", latestKey + " now points at " + releaseId);
        LogFile.keep(workspace, "publish-" + tag, log);
    }

    /** What ended up in the release, and how the base tree and the overlay were narrowed to it. */
    private static String contents(int added, int replaced, RemoveRules rules, RemoveRules.Outcome outcome,
            Map<String, Long> files) {
        StringBuilder text = new StringBuilder(
                Log.count(files.size(), "file") + ", " + Sizes.human(total(files)));
        List<String> parts = new ArrayList<>();
        if (added + replaced > 0) {
            parts.add(Log.count(added, "file") + " added, " + Log.count(replaced, "file") + " replaced");
        }
        if (rules.size() > 0) {
            parts.add(Log.count(outcome.removed().size(), "file") + " removed by " + Log.count(rules.size(), "rule"));
        }
        if (!outcome.unmatched().isEmpty()) {
            parts.add(Log.count(outcome.unmatched().size(), "rule") + " matched nothing");
        }
        if (!parts.isEmpty()) {
            text.append(" (").append(String.join("; ", parts)).append(")");
        }
        return text.toString();
    }

    private static String uploaded(int toUpload, int uploaded, int skipped, long bytes, long nanos) {
        if (toUpload == 0) {
            return skipped == 0 ? "nothing to upload" : "nothing to upload; " + Log.count(skipped, "chunk") + " already stored";
        }
        return Log.count(uploaded, "chunk") + ", " + Sizes.human(bytes) + " in " + Log.duration(nanos)
                + " (" + Sizes.rate(bytes, nanos) + ")"
                + (skipped > 0 ? "; " + Log.count(skipped, "chunk") + " already stored" : "");
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

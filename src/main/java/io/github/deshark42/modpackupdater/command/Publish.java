package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sizes;
import io.github.deshark42.modpackupdater.fs.Tree;
import io.github.deshark42.modpackupdater.log.Log;
import io.github.deshark42.modpackupdater.log.LogFile;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publish one complete release of a tag: merge the tag's base tree with its overlay, drop what
 * remove.txt takes out of base, upload the files that are not in the bucket yet, then write the
 * release manifest and finally the latest pointer.
 *
 * <p>The run is reported in three stages: what goes into the release, what moves to the bucket,
 * and what is written to record it.
 */
public final class Publish {

    private Publish() {
    }

    public static void run(Path workspace, String tag, String releaseId, boolean dryRun, Log log) {
        Names.requireTag(tag);
        Names.requireReleaseId(releaseId);
        log.headline("publish " + tag + " " + releaseId);
        log.phase("inputs");
        Path base = Layout.baseDir(workspace, tag);
        Path overlay = Layout.overlayDir(workspace, tag);
        Map<String, FileEntry> baseFiles = Tree.scan(base);
        log.step("scan", Log.relativeTo(workspace, base),
                Log.count(baseFiles.size(), "file") + ", " + Sizes.human(total(baseFiles)));
        boolean hasOverlay = Files.isDirectory(overlay);
        Map<String, FileEntry> overlayFiles = hasOverlay ? Tree.scan(overlay) : Map.of();
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
        Map<String, FileEntry> files = new TreeMap<>(baseFiles);
        files.keySet().removeAll(outcome.removed());
        Set<String> overlaid = new LinkedHashSet<>();
        int added = 0;
        int replaced = 0;
        for (Map.Entry<String, FileEntry> entry : overlayFiles.entrySet()) {
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
        Manifest manifest = new Manifest(tag, releaseId, Instant.now().truncatedTo(ChronoUnit.SECONDS),
                Manifest.SHA256, files);
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
        BlobCache cache = BlobCache.open(workspace, config, log);

        // Ask which blobs are missing before uploading, so progress can count them. Blobs the
        // cache already knows cost no request at all.
        Map<String, String> distinct = new LinkedHashMap<>();
        for (Map.Entry<String, FileEntry> entry : files.entrySet()) {
            distinct.putIfAbsent(entry.getValue().hash(), entry.getKey());
        }
        Map<String, String> pending = new ConcurrentHashMap<>();
        Parallel.forEach(distinct.entrySet(), config.concurrency(), entry -> {
            if (!cache.exists(store, entry.getKey())) {
                pending.put(entry.getKey(), entry.getValue());
            }
        });

        int toUpload = pending.size();
        int skipped = distinct.size() - toUpload;
        long pendingBytes = 0;
        for (String path : pending.values()) {
            pendingBytes += files.get(path).size();
        }
        log.step("plan", Log.count(toUpload, "blob") + " to upload, " + Log.count(skipped, "blob") + " already there",
                Sizes.human(pendingBytes) + " to upload");
        AtomicInteger uploaded = new AtomicInteger();
        AtomicLong uploadedBytes = new AtomicLong();
        long uploadStartedAt = System.nanoTime();
        Parallel.forEach(pending.entrySet(), config.concurrency(), entry -> {
            Path root = overlaid.contains(entry.getValue()) ? overlay : base;
            store.putFile(ObjectKeys.blob(entry.getKey()), SafePath.resolve(root, entry.getValue()));
            cache.remember(entry.getKey());
            log.item("upload", entry.getValue());
            int done = uploaded.incrementAndGet();
            long bytes = uploadedBytes.addAndGet(files.get(entry.getValue()).size());
            log.progress("upload", done, toUpload, bytes, "blob");
        });
        long uploadNanos = System.nanoTime() - uploadStartedAt;
        if (toUpload > 0) {
            log.step("upload", Log.count(uploaded.get(), "blob"),
                    Sizes.human(uploadedBytes.get()) + " in " + Log.duration(uploadNanos)
                            + " (" + Sizes.rate(uploadedBytes.get(), uploadNanos) + ")");
        }
        if (skipped > 0) {
            log.step("skip", Log.count(skipped, "blob"), "already in the bucket");
        }

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
            Map<String, FileEntry> files) {
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
            return skipped == 0 ? "nothing to upload" : "nothing to upload; " + Log.count(skipped, "blob") + " already stored";
        }
        return Log.count(uploaded, "blob") + ", " + Sizes.human(bytes) + " in " + Log.duration(nanos)
                + " (" + Sizes.rate(bytes, nanos) + ")"
                + (skipped > 0 ? "; " + Log.count(skipped, "blob") + " already stored" : "");
    }

    private static long total(Map<String, FileEntry> files) {
        long bytes = 0;
        for (FileEntry entry : files.values()) {
            bytes += entry.size();
        }
        return bytes;
    }

    private static String write(S3Store store, String key, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        store.putBytes(key, bytes);
        return Sizes.human(bytes.length);
    }
}

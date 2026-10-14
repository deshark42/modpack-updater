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
import io.github.deshark42.modpackupdater.s3.S3Store;
import io.github.deshark42.modpackupdater.util.Parallel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 */
public final class Publish {

    private Publish() {
    }

    public static void run(Path workspace, String tag, String releaseId, boolean dryRun, Log log) {
        Names.requireTag(tag);
        Names.requireReleaseId(releaseId);
        log.headline("publish " + tag + " " + releaseId);
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
        if (dryRun) {
            log.dryRun();
            return;
        }

        Config config = Workspace.load(workspace, log);
        S3Store store = new S3Store(config);
        String releaseKey = ObjectKeys.release(tag, releaseId);
        if (store.exists(releaseKey)) {
            throw new UserError("release " + releaseId + " of tag " + tag
                    + " already exists; a published release is never rewritten");
        }

        // Ask which blobs are missing before uploading, so progress can count them.
        Map<String, String> distinct = new LinkedHashMap<>();
        for (Map.Entry<String, FileEntry> entry : files.entrySet()) {
            distinct.putIfAbsent(entry.getValue().hash(), entry.getKey());
        }
        Map<String, String> pending = new ConcurrentHashMap<>();
        Parallel.forEach(distinct.entrySet(), config.concurrency(), entry -> {
            if (!store.exists(ObjectKeys.blob(entry.getKey()))) {
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
        Parallel.forEach(pending.entrySet(), config.concurrency(), entry -> {
            Path root = overlaid.contains(entry.getValue()) ? overlay : base;
            store.putFile(ObjectKeys.blob(entry.getKey()), SafePath.resolve(root, entry.getValue()));
            log.item("upload", entry.getValue());
            int done = uploaded.incrementAndGet();
            long bytes = uploadedBytes.addAndGet(files.get(entry.getValue()).size());
            if (log.progressDue()) {
                log.progress("upload", done + "/" + toUpload + " blobs", Sizes.human(bytes));
            }
        });
        log.step("upload", Log.count(uploaded.get(), "blob"), Sizes.human(uploadedBytes.get()));
        log.step("skip", Log.count(skipped, "blob"), "already in the bucket");

        log.step("write", releaseKey, write(store, releaseKey, manifest.toJson()));
        String latestKey = ObjectKeys.latest(tag);
        log.step("write", latestKey, write(store, latestKey, new Pointer(releaseId).toJson()));
        log.done(tag + " " + releaseId + " is now the latest");
        LogFile.keep(workspace, "publish-" + tag, log);
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

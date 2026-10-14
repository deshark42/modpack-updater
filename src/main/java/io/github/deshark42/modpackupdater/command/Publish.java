package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sizes;
import io.github.deshark42.modpackupdater.fs.Tree;
import io.github.deshark42.modpackupdater.log.Log;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.FileEntry;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.Names;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import io.github.deshark42.modpackupdater.model.Pointer;
import io.github.deshark42.modpackupdater.model.SafePath;
import io.github.deshark42.modpackupdater.s3.S3Store;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Publish one complete release of a tag: hash the tag's base tree, upload the files that are not in
 * the bucket yet, then write the release manifest and finally the latest pointer.
 */
public final class Publish {

    private Publish() {
    }

    public static void run(Path workspace, String tag, String releaseId, boolean dryRun, Log log) {
        Names.requireTag(tag);
        Names.requireReleaseId(releaseId);
        log.headline("publish " + tag + " " + releaseId);
        Path base = Layout.baseDir(workspace, tag);
        Map<String, FileEntry> files = Tree.scan(base);
        Manifest manifest = new Manifest(tag, releaseId, Instant.now().truncatedTo(ChronoUnit.SECONDS),
                Manifest.SHA256, files);
        log.step("scan", Log.relativeTo(workspace, base),
                Log.count(files.size(), "file") + ", " + Sizes.human(manifest.totalSize()));
        if (dryRun) {
            log.dryRun();
            return;
        }

        S3Store store = new S3Store(Config.load(workspace));
        String releaseKey = ObjectKeys.release(tag, releaseId);
        if (store.exists(releaseKey)) {
            throw new UserError("release " + releaseId + " of tag " + tag
                    + " already exists; a published release is never rewritten");
        }

        // Ask which blobs are missing before uploading, so progress can count them.
        Map<String, String> pending = new LinkedHashMap<>();
        int present = 0;
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, FileEntry> entry : files.entrySet()) {
            String hash = entry.getValue().hash();
            if (!seen.add(hash)) {
                continue;
            }
            if (store.exists(ObjectKeys.blob(hash))) {
                present++;
            } else {
                pending.put(hash, entry.getKey());
            }
        }

        int uploaded = 0;
        long uploadedBytes = 0;
        for (Map.Entry<String, String> entry : pending.entrySet()) {
            store.putFile(ObjectKeys.blob(entry.getKey()), SafePath.resolve(base, entry.getValue()));
            log.item("upload", entry.getValue());
            uploaded++;
            uploadedBytes += files.get(entry.getValue()).size();
            if (log.progressDue()) {
                log.step("upload", uploaded + "/" + pending.size() + " blobs", Sizes.human(uploadedBytes));
            }
        }
        log.step("upload", Log.count(uploaded, "blob"), Sizes.human(uploadedBytes));
        log.step("reuse", Log.count(present, "blob"), "already in the bucket");

        log.step("write", releaseKey, write(store, releaseKey, manifest.toJson()));
        String latestKey = ObjectKeys.latest(tag);
        log.step("write", latestKey, write(store, latestKey, new Pointer(releaseId).toJson()));
        log.done(tag + " " + releaseId + " is now the latest");
    }

    private static String write(S3Store store, String key, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        store.putBytes(key, bytes);
        return Sizes.human(bytes.length);
    }
}

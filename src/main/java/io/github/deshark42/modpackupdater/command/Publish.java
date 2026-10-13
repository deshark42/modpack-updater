package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sizes;
import io.github.deshark42.modpackupdater.fs.Tree;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.FileEntry;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.Names;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import io.github.deshark42.modpackupdater.model.Pointer;
import io.github.deshark42.modpackupdater.model.SafePath;
import io.github.deshark42.modpackupdater.s3.S3Store;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Publish one complete release of a tag: hash the tag's base tree, upload the files that are not in
 * the bucket yet, then write the release manifest and finally the latest pointer.
 */
public final class Publish {

    private Publish() {
    }

    public static void run(Path workspace, String tag, String releaseId, boolean dryRun, PrintStream out) {
        Names.requireTag(tag);
        Names.requireReleaseId(releaseId);
        Path base = Layout.baseDir(workspace, tag);
        Map<String, FileEntry> files = Tree.scan(base);
        Manifest manifest = new Manifest(tag, releaseId, Instant.now().truncatedTo(ChronoUnit.SECONDS),
                Manifest.SHA256, files);
        out.println("tag " + tag + " release " + releaseId + ": " + count(files.size()) + ", "
                + Sizes.human(manifest.totalSize()));
        if (dryRun) {
            out.println("dry run: nothing was uploaded");
            return;
        }

        S3Store store = new S3Store(Config.load(workspace));
        String releaseKey = ObjectKeys.release(tag, releaseId);
        if (store.exists(releaseKey)) {
            throw new UserError("release " + releaseId + " of tag " + tag
                    + " already exists; a published release is never rewritten");
        }

        int uploaded = 0;
        int present = 0;
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, FileEntry> entry : files.entrySet()) {
            String hash = entry.getValue().hash();
            if (!seen.add(hash)) {
                continue;
            }
            String blob = ObjectKeys.blob(hash);
            if (store.exists(blob)) {
                present++;
                continue;
            }
            store.putFile(blob, SafePath.resolve(base, entry.getKey()));
            uploaded++;
        }
        out.println("uploaded " + uploaded + " files, " + present + " were already stored");

        store.putBytes(releaseKey, manifest.toJson().getBytes(StandardCharsets.UTF_8));
        store.putBytes(ObjectKeys.latest(tag), new Pointer(releaseId).toJson().getBytes(StandardCharsets.UTF_8));
        out.println("release " + releaseId + " is now the latest of tag " + tag);
    }

    private static String count(int files) {
        return files + (files == 1 ? " file" : " files");
    }
}

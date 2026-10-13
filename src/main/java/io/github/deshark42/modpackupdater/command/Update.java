package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import io.github.deshark42.modpackupdater.fs.Sizes;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.FileEntry;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.Names;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import io.github.deshark42.modpackupdater.model.Plan;
import io.github.deshark42.modpackupdater.model.Pointer;
import io.github.deshark42.modpackupdater.model.SafePath;
import io.github.deshark42.modpackupdater.s3.S3Store;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Update one tag to the release the bucket says is latest: diff the applied manifest against the
 * target one, move the files that change out of the way, download, then record the new state.
 */
public final class Update {

    private Update() {
    }

    public static void run(Path workspace, String tag, boolean dryRun, PrintStream out) {
        Names.requireTag(tag);
        S3Store store = new S3Store(Config.load(workspace));

        String latestKey = ObjectKeys.latest(tag);
        Pointer latest = Pointer.parse(new String(store.getBytes(latestKey), StandardCharsets.UTF_8),
                store.key(latestKey));

        String applied = null;
        Manifest local = null;
        Path currentFile = Layout.currentFile(workspace, tag);
        if (Files.exists(currentFile)) {
            applied = Pointer.read(currentFile).releaseId();
            Path appliedManifest = Layout.releaseFile(workspace, tag, applied);
            if (!Files.isRegularFile(appliedManifest)) {
                throw new UserError("cannot tell which files are already applied: " + appliedManifest
                        + " is missing; restore it or delete " + currentFile);
            }
            local = Manifest.read(appliedManifest);
        }

        if (latest.releaseId().equals(applied)) {
            out.println("tag " + tag + " is already at " + applied);
            return;
        }

        String targetKey = ObjectKeys.release(tag, latest.releaseId());
        Manifest target = Manifest.parse(new String(store.getBytes(targetKey), StandardCharsets.UTF_8),
                store.key(targetKey));
        Plan plan = Plan.between(local, target);
        out.println("tag " + tag + ": " + (applied == null ? "first update" : applied + " -> " + target.releaseId())
                + " (" + plan.describe() + ", " + Sizes.human(plan.downloadSize()) + " to download)");
        for (String path : plan.added()) {
            out.println("  add     " + path);
        }
        for (String path : plan.replaced()) {
            out.println("  replace " + path);
        }
        for (String path : plan.deleted()) {
            out.println("  delete  " + path);
        }
        if (dryRun) {
            out.println("dry run: nothing was written");
            return;
        }

        Path backup = applied == null ? null : Layout.backupDir(workspace, tag, applied);
        for (String path : plan.deleted()) {
            moveAside(workspace, backup, path);
        }
        for (String path : plan.replaced()) {
            moveAside(workspace, backup, path);
        }
        for (String path : plan.downloads()) {
            FileEntry entry = target.files().get(path);
            store.download(ObjectKeys.blob(entry.hash()), SafePath.resolve(workspace, path));
        }

        target.write(Layout.releaseFile(workspace, tag, target.releaseId()));
        new Pointer(target.releaseId()).write(currentFile);
        out.println("tag " + tag + " is now at " + target.releaseId()
                + (backup == null ? "" : "; what it replaced is in " + backup));
    }

    private static void moveAside(Path workspace, Path backup, String path) {
        Path source = SafePath.resolve(workspace, path);
        if (backup == null || !Files.exists(source)) {
            return;
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
    }
}

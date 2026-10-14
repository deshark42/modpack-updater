package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import io.github.deshark42.modpackupdater.fs.Sizes;
import io.github.deshark42.modpackupdater.log.Log;
import io.github.deshark42.modpackupdater.log.LogFile;
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
import io.github.deshark42.modpackupdater.util.Parallel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Update one tag to the release the bucket says is latest: diff the applied manifest against the
 * target one, move the files that change out of the way, download, then record the new state.
 */
public final class Update {

    private Update() {
    }

    public static void run(Path workspace, String tag, boolean dryRun, Log log) {
        Names.requireTag(tag);
        // a dry run writes nothing at all, not even the defaults this file is missing
        Config config = dryRun ? Config.load(workspace) : Workspace.load(workspace, log);
        S3Store store = new S3Store(config);

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
            log.done(tag + " is already at " + applied);
            return;
        }

        String targetKey = ObjectKeys.release(tag, latest.releaseId());
        Manifest target = Manifest.parse(new String(store.getBytes(targetKey), StandardCharsets.UTF_8),
                store.key(targetKey));
        Plan plan = Plan.between(local, target);
        log.headline("update " + tag + (applied == null ? " -> " : " " + applied + " -> ") + target.releaseId());
        log.step("plan", plan.describe(), Sizes.human(plan.downloadSize()) + " to download");
        for (String path : plan.added()) {
            log.item("add", path);
        }
        for (String path : plan.replaced()) {
            log.item("replace", path);
        }
        for (String path : plan.deleted()) {
            log.item("delete", path);
        }
        if (dryRun) {
            log.dryRun();
            return;
        }

        Path backup = applied == null ? null : Layout.backupDir(workspace, tag, applied);
        int moved = 0;
        for (String path : plan.deleted()) {
            moved += moveAside(workspace, backup, path) ? 1 : 0;
        }
        for (String path : plan.replaced()) {
            moved += moveAside(workspace, backup, path) ? 1 : 0;
        }
        if (backup != null) {
            log.step("backup", Log.count(moved, "file"), Log.relativeTo(workspace, backup));
        }

        List<String> downloads = plan.downloads();
        int total = downloads.size();
        AtomicInteger downloaded = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        Parallel.forEach(downloads, config.concurrency(), path -> {
            FileEntry entry = target.files().get(path);
            store.download(ObjectKeys.blob(entry.hash()), SafePath.resolve(workspace, path));
            int done = downloaded.incrementAndGet();
            long sofar = bytes.addAndGet(entry.size());
            if (log.progressDue()) {
                log.progress("download", done + "/" + total + " files", Sizes.human(sofar));
            }
        });

        if (total > 0) {
            log.step("download", Log.count(downloaded.get(), "file"), Sizes.human(bytes.get()));
        }

        Path releaseFile = Layout.releaseFile(workspace, tag, target.releaseId());
        target.write(releaseFile);
        new Pointer(target.releaseId()).write(currentFile);
        log.step("write", Log.relativeTo(workspace, releaseFile));
        log.step("write", Log.relativeTo(workspace, currentFile));
        log.done(tag + " is now at " + target.releaseId());
        if (!plan.isEmpty()) {
            LogFile.keep(workspace, "update-" + tag, log);
        }
    }

    private static boolean moveAside(Path workspace, Path backup, String path) {
        Path source = SafePath.resolve(workspace, path);
        if (backup == null || !Files.exists(source)) {
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
}

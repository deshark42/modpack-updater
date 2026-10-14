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
 *
 * <p>The run is reported in three stages: what the diff came to, what it does to the workspace,
 * and what is written to record the new state.
 */
public final class Update {

    private Update() {
    }

    public static void run(Path workspace, String tag, boolean dryRun, Log log) {
        Names.requireTag(tag);
        log.headline("update " + tag);
        log.phase("plan");
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
            log.step("latest", tag + " is already at " + applied);
            log.done(tag + " is already at " + applied);
            log.summary("version", applied + ", unchanged");
            return;
        }

        String targetKey = ObjectKeys.release(tag, latest.releaseId());
        Manifest target = Manifest.parse(new String(store.getBytes(targetKey), StandardCharsets.UTF_8),
                store.key(targetKey));
        Plan plan = Plan.between(local, target);
        log.step("latest", applied == null
                ? tag + " -> " + target.releaseId()
                : tag + " " + applied + " -> " + target.releaseId(),
                applied == null ? "first update of this tag" : "");
        log.step("files", plan.describe(),
                plan.downloadSize() > 0 ? Sizes.human(plan.downloadSize()) + " to download" : "");
        for (String path : plan.added()) {
            log.item("add", path);
        }
        for (String path : plan.replaced()) {
            log.item("replace", path);
        }
        for (String path : plan.deleted()) {
            log.item("delete", path);
        }
        String version = applied == null
                ? "first update -> " + target.releaseId()
                : applied + " -> " + target.releaseId();
        String changed = Log.count(plan.size(), "file") + " (" + plan.describe() + ")"
                + (plan.downloadSize() > 0
                        ? ", " + Sizes.human(plan.downloadSize()) + (dryRun ? " to download" : " downloaded")
                        : "");
        if (dryRun) {
            log.dryRun();
            log.summary("version", version);
            log.summary("changed", changed);
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

        List<String> downloads = plan.downloads();
        int total = downloads.size();
        if (moved > 0 || total > 0) {
            log.phase("apply");
        }
        if (moved > 0) {
            log.step("backup", Log.count(moved, "file"), Log.relativeTo(workspace, backup));
        }

        AtomicInteger downloaded = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        long downloadStartedAt = System.nanoTime();
        Parallel.forEach(downloads, config.concurrency(), path -> {
            FileEntry entry = target.files().get(path);
            store.download(ObjectKeys.blob(entry.hash()), SafePath.resolve(workspace, path));
            int done = downloaded.incrementAndGet();
            long sofar = bytes.addAndGet(entry.size());
            log.progress("download", done, total, sofar, "file");
        });
        long downloadNanos = System.nanoTime() - downloadStartedAt;

        if (total > 0) {
            log.step("download", Log.count(downloaded.get(), "file"),
                    Sizes.human(bytes.get()) + " in " + Log.duration(downloadNanos)
                            + " (" + Sizes.rate(bytes.get(), downloadNanos) + ")");
        }

        log.phase("commit");
        Path releaseFile = Layout.releaseFile(workspace, tag, target.releaseId());
        target.write(releaseFile);
        new Pointer(target.releaseId()).write(currentFile);
        log.step("write", Log.relativeTo(workspace, releaseFile));
        log.step("write", Log.relativeTo(workspace, currentFile));
        log.done(tag + " is now at " + target.releaseId());
        log.summary("version", version);
        log.summary("changed", changed);
        if (moved > 0) {
            log.summary("backup", Log.count(moved, "file") + " in " + Log.relativeTo(workspace, backup));
        }
        log.summary("release", Log.relativeTo(workspace, releaseFile));
        log.summary("state", Log.relativeTo(workspace, currentFile) + " now points at " + target.releaseId());
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

package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import io.github.deshark42.modpackupdater.fs.Chunker;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.fs.Sizes;
import io.github.deshark42.modpackupdater.log.Log;
import io.github.deshark42.modpackupdater.log.LogFile;
import io.github.deshark42.modpackupdater.model.ChunkTable;
import io.github.deshark42.modpackupdater.model.Chunking;
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
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

/**
 * Update one tag to the release the bucket says is latest, or to the one named on the command line:
 * diff the applied manifest against the target one, move the files that change out of the way, fetch
 * only the chunks the workspace does not already have, then assemble the new files and record the new
 * state.
 *
 * <p>The chunk tables say how each target file is split; splitting the copies the previous release
 * left in {@code backup/} with the same parameters finds the chunks already on disk, so a changed
 * file only fetches the chunks around the change. A copy that is missing or unreadable simply
 * contributes no chunks, and the file is fetched whole.
 *
 * <p>The run is reported in three stages: what the diff came to, what it does to the workspace,
 * and what is written to record the new state.
 */
public final class Update {

    private Update() {
    }

    public static void run(Path workspace, String tag, String releaseId, boolean dryRun, Log log) {
        Names.requireTag(tag);
        if (releaseId != null) {
            Names.requireReleaseId(releaseId);
        }
        log.headline("update " + tag);
        log.phase("plan");
        // a dry run writes nothing at all, not even the defaults this file is missing
        Config config = dryRun ? Config.load(workspace) : Workspace.load(workspace, log);
        S3Store store = new S3Store(config);

        // an explicit release id bypasses latest.json entirely, so a pinned update works even while
        // the pointer is missing or names a release the caller does not want
        String targetId = releaseId;
        if (targetId == null) {
            String latestKey = ObjectKeys.latest(tag);
            Pointer latest = Pointer.parse(new String(store.getBytes(latestKey), StandardCharsets.UTF_8),
                    store.key(latestKey));
            targetId = latest.releaseId();
        }
        String verb = releaseId == null ? "latest" : "release";

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

        if (targetId.equals(applied)) {
            log.step(verb, tag + " is already at " + applied);
            log.done(tag + " is already at " + applied);
            log.summary("version", applied + ", unchanged");
            return;
        }

        String targetKey = ObjectKeys.release(tag, targetId);
        Manifest target = Manifest.parse(new String(store.getBytes(targetKey), StandardCharsets.UTF_8),
                store.key(targetKey));
        Plan plan = Plan.between(local, target);
        log.step(verb, applied == null
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
        if (dryRun) {
            log.dryRun();
            log.summary("version", version);
            log.summary("changed", changed(plan, plan.downloadSize(), "to download"));
            return;
        }

        Path backups = Layout.backupsDir(workspace, tag);
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

        int reused = 0;
        long downloadedBytes = 0;
        Path tempDir = null;
        if (total > 0) {
            try {
                Map<String, ChunkTable> tables = chunkTables(store, target, downloads, config.concurrency());
                Reuse reuse = localChunks(backups, applied, plan, targetId, target, tables,
                        config.concurrency());
                Map<String, Local> existing = reuse.chunks();
                Set<String> needed = new LinkedHashSet<>();
                for (String path : downloads) {
                    needed.addAll(tables.get(target.files().get(path).chunks()).chunks());
                }
                List<String> missing = new ArrayList<>();
                for (String hash : needed) {
                    if (!existing.containsKey(hash)) {
                        missing.add(hash);
                    }
                }
                reused = needed.size() - missing.size();
                if (reused > 0) {
                    log.step("chunk", Log.count(reuse.files(), "file"),
                            Log.count(reused, "chunk") + " reused from " + Log.relativeTo(workspace, backups));
                }
                if (!missing.isEmpty()) {
                    Files.createDirectories(Layout.tempDir(workspace));
                    tempDir = Files.createTempDirectory(Layout.tempDir(workspace), "update-");
                    downloadedBytes = download(store, missing, tempDir, config.concurrency(), log);
                }
                assembleAll(workspace, downloads, target, tables, existing, tempDir, config.concurrency());
            } catch (IOException e) {
                throw new UserError("cannot stage chunks under " + Layout.tempDir(workspace) + ": "
                        + e.getMessage(), e);
            } finally {
                deleteTree(tempDir);
            }
        }

        log.phase("commit");
        Path releaseFile = Layout.releaseFile(workspace, tag, target.releaseId());
        target.write(releaseFile);
        new Pointer(target.releaseId()).write(currentFile);
        log.step("write", Log.relativeTo(workspace, releaseFile));
        log.step("write", Log.relativeTo(workspace, currentFile));
        log.done(tag + " is now at " + target.releaseId());
        log.summary("version", version);
        log.summary("changed", changed(plan, downloadedBytes, "downloaded"));
        log.summary("reuse", Log.count(reused, "chunk")
                + (reused > 0 ? " from " + Log.relativeTo(workspace, backups) : ""));
        if (moved > 0) {
            log.summary("backup", Log.count(moved, "file") + " in " + Log.relativeTo(workspace, backup));
        }
        log.summary("release", Log.relativeTo(workspace, releaseFile));
        log.summary("state", Log.relativeTo(workspace, currentFile) + " now points at " + target.releaseId());
        if (!plan.isEmpty()) {
            LogFile.keep(workspace, "update-" + tag, log);
        }
    }

    /** The closing line's account of what the run changed and how much of it moved over the wire. */
    private static String changed(Plan plan, long bytes, String how) {
        return Log.count(plan.size(), "file") + " (" + plan.describe() + ")"
                + (bytes > 0 ? ", " + Sizes.human(bytes) + " " + how : "");
    }

    /**
     * The chunk table of every file that has to be assembled, asked for once per distinct table and
     * checked against the manifest so a misaddressed object cannot be split into a wrong file.
     */
    private static Map<String, ChunkTable> chunkTables(S3Store store, Manifest target, List<String> downloads,
            int concurrency) {
        Set<String> ids = new TreeSet<>();
        for (String path : downloads) {
            ids.add(target.files().get(path).chunks());
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
            ChunkTable table = tables.get(entry.chunks());
            if (table.size() != entry.size()) {
                throw new UserError("the chunk table of " + path + " covers " + table.size()
                        + " bytes, the manifest says " + entry.size());
            }
        }
        return tables;
    }

    /** A chunk already on disk, and the file its bytes can be read back from. */
    private record Local(Path file, Chunker.Cut cut) {
    }

    /** The chunks the previous release's copies still hold, and how many of those copies were read. */
    private record Reuse(Map<String, Local> chunks, int files) {
    }

    /**
     * Splits the copies left in {@code backup/} with the parameters the target chunk tables record,
     * collecting every chunk the new release could reuse. Two kinds of copies qualify: the ones this
     * run moved aside, under the paths they were removed from, and the ones an earlier run moved aside
     * when it left the release being applied now, which is what makes a rollback nearly free. A path
     * without a target table of its own, a deleted one or a kept copy addressed by its old path, is
     * split with every chunking the run's target tables use. A copy that is missing or cannot be read
     * contributes nothing.
     */
    private static Reuse localChunks(Path backups, String applied, Plan plan, String targetId,
            Manifest target, Map<String, ChunkTable> tables, int concurrency) {
        Map<String, Local> existing = new ConcurrentHashMap<>();
        Set<Chunking> targetChunkings = new LinkedHashSet<>();
        for (ChunkTable table : tables.values()) {
            targetChunkings.add(table.chunking());
        }
        if (targetChunkings.isEmpty()) {
            return new Reuse(existing, 0);
        }
        AtomicInteger read = new AtomicInteger();
        if (applied != null) {
            Path backup = backups.resolve(Names.requireReleaseId(applied));
            List<String> moved = new ArrayList<>(plan.replaced());
            moved.addAll(plan.deleted());
            Parallel.forEach(moved, concurrency, path -> {
                Path copy = SafePath.resolve(backup, path);
                if (!Files.isRegularFile(copy)) {
                    return;
                }
                Map<String, Local> found = new HashMap<>();
                try {
                    for (Chunking chunking : chunkingsFor(path, target, tables, targetChunkings)) {
                        Chunker.split(copy, chunking, cut -> found.putIfAbsent(cut.hash(), new Local(copy, cut)));
                    }
                } catch (UserError e) {
                    return; // a copy that cannot be read is as good as no copy at all
                }
                read.incrementAndGet();
                for (Map.Entry<String, Local> chunk : found.entrySet()) {
                    existing.putIfAbsent(chunk.getKey(), chunk.getValue());
                }
            });
        }
        Path kept = backups.resolve(Names.requireReleaseId(targetId));
        List<Path> keptCopies;
        try (var walk = Files.walk(kept)) {
            keptCopies = walk.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            return new Reuse(existing, read.get()); // a kept directory that cannot be listed only costs reuse
        }
        Parallel.forEach(keptCopies, concurrency, copy -> {
            String path = kept.relativize(copy).toString().replace('\\', '/');
            Map<String, Local> found = new HashMap<>();
            try {
                for (Chunking chunking : chunkingsFor(path, target, tables, targetChunkings)) {
                    Chunker.split(copy, chunking, cut -> found.putIfAbsent(cut.hash(), new Local(copy, cut)));
                }
            } catch (UserError e) {
                return;
            }
            read.incrementAndGet();
            for (Map.Entry<String, Local> chunk : found.entrySet()) {
                existing.putIfAbsent(chunk.getKey(), chunk.getValue());
            }
        });
        return new Reuse(existing, read.get());
    }

    /** The parameters the target table of one path records, or every one the run's target tables use. */
    private static List<Chunking> chunkingsFor(String path, Manifest target, Map<String, ChunkTable> tables,
            Set<Chunking> all) {
        FileEntry entry = target.files().get(path);
        ChunkTable table = entry == null ? null : tables.get(entry.chunks());
        return table == null ? List.copyOf(all) : List.of(table.chunking());
    }

    /** Fetches the missing chunks into the run's temporary directory, one file per chunk. */
    private static long download(S3Store store, List<String> missing, Path tempDir, int concurrency, Log log) {
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
            log.progress("download", done.incrementAndGet(), total, bytes.addAndGet(length), "chunk");
        });
        long nanos = System.nanoTime() - startedAt;
        log.step("download", Log.count(done.get(), "chunk"), Sizes.human(bytes.get()) + " in "
                + Log.duration(nanos) + " (" + Sizes.rate(bytes.get(), nanos) + ")");
        return bytes.get();
    }

    /** Writes every fetched file, each one on its own, from the chunks the run has. */
    private static void assembleAll(Path workspace, List<String> downloads, Manifest target,
            Map<String, ChunkTable> tables, Map<String, Local> existing, Path tempDir, int concurrency) {
        Parallel.forEach(downloads, concurrency, path -> {
            FileEntry entry = target.files().get(path);
            assemble(workspace, path, entry, tables.get(entry.chunks()), existing, tempDir);
        });
    }

    /**
     * Writes one file from its chunks in table order, hashing as it goes, and moves it into place
     * only once the whole-file hash matches the manifest. The bytes are written next to the
     * destination first, so a failed assembly leaves neither a partial target nor a replaced one.
     */
    private static void assemble(Path workspace, String path, FileEntry entry, ChunkTable table,
            Map<String, Local> existing, Path tempDir) {
        Path target = SafePath.resolve(workspace, path);
        Path temp = null;
        Map<Path, FileChannel> sources = new HashMap<>();
        try {
            Path parent = target.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, ".update-", ".part");
            MessageDigest digest = digest();
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                for (String hash : table.chunks()) {
                    Local local = existing.get(hash);
                    byte[] bytes = local == null
                            ? Files.readAllBytes(tempDir.resolve(hash))
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
            AtomicFile.move(temp, target);
            temp = null;
        } catch (IOException e) {
            throw new UserError("cannot write " + target + ": " + e.getMessage(), e);
        } finally {
            for (FileChannel channel : sources.values()) {
                closeQuietly(channel);
            }
            deleteQuietly(temp);
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

    private static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
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

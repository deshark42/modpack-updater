package io.github.deshark42.modpackupdater.fs;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.ChunkTable;
import io.github.deshark42.modpackupdater.model.Chunking;
import io.github.deshark42.modpackupdater.model.FileEntry;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.Plan;
import io.github.deshark42.modpackupdater.model.SafePath;
import io.github.deshark42.modpackupdater.util.Parallel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Finds target content and reusable chunks in the real workspace and its kept copies. */
public final class LocalContent {
    private LocalContent() {
    }

    public static Set<Path> deletionPaths(Path workspace, List<String> deleted) {
        Set<Path> paths = new HashSet<>();
        for (String path : deleted) {
            // These paths already passed manifest preflight. Resolve the leaf only for this mutating plan:
            // two permitted spellings can name the same directory entry even though Plan keys differ.
            Path source = workspace.resolve(SafePath.require(path));
            if (!exists(source)) {
                continue;
            }
            try {
                paths.add(source.toRealPath());
            } catch (NoSuchFileException e) {
                // A missing deletion will not move any target content.
            } catch (IOException e) {
                throw new UserError("cannot resolve planned deletion " + path + ": " + e.getMessage(), e);
            }
        }
        return Set.copyOf(paths);
    }

    /** Whether matching content will remain at the target after planned deletions. */
    public static boolean matchesTarget(Path workspace, String path, FileEntry entry, Set<Path> deletions) {
        Path target = SafePath.resolve(workspace, path);
        try {
            if (!Files.isRegularFile(target) || Files.size(target) != entry.size()) {
                return false;
            }
            if (!Sha256.ofFile(target).equals(entry.hash())) {
                return false;
            }
            if (!deletions.isEmpty()) {
                // Moving a directory also moves matching targets below it. A threatened file must go
                // through staging before any deletion, even when no payload chunks need downloading.
                for (Path current = target.toRealPath(); current != null; current = current.getParent()) {
                    if (deletions.contains(current)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException | UserError e) {
            // a path that cannot be read is treated as different, so it is fetched whole
            return false;
        }
    }

    /** Whether something is at this path, including a symbolic link whose target does not exist. */
    private static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    /** A chunk already on disk, and the file its bytes can be read back from. */
    public record Local(Path file, Chunker.Cut cut) {
    }

    /** The chunks the workspace's own files still hold, and how many of those files were read. */
    public record Reuse(Map<String, Local> chunks, int files) {
    }

    /**
     * Splits the local files this run is about to change or delete, plus the copies an earlier run
     * kept under the target release, with the parameters the target chunk tables record, collecting
     * every chunk the new release could reuse. The target path's current file is the first source; a
     * missing or unreadable one falls back to the copy an interrupted earlier run may have left in
     * {@code backup/}. A path without a target table of its own - a deleted one, or a kept copy
     * addressed by its old path - is split with every chunking the run's target tables use. A copy
     * that is missing or cannot be read contributes nothing.
     */
    public static Reuse localChunks(Path workspace, Path backup, Plan plan, List<String> toFetch, String tag,
            String targetId, Manifest target, Map<String, ChunkTable> tables, int concurrency) {
        Map<String, Local> existing = new ConcurrentHashMap<>();
        Set<Chunking> targetChunkings = new LinkedHashSet<>();
        for (ChunkTable table : tables.values()) {
            targetChunkings.add(table.chunking());
        }
        if (targetChunkings.isEmpty()) {
            return new Reuse(existing, 0);
        }
        AtomicInteger read = new AtomicInteger();
        List<String> changing = new ArrayList<>(toFetch);
        changing.addAll(plan.deleted());
        Parallel.forEach(changing, concurrency, path -> {
            List<Chunking> chunkings = chunkingsFor(path, target, tables, targetChunkings);
            if (cutInto(SafePath.resolve(workspace, path), chunkings, existing)) {
                read.incrementAndGet();
                return;
            }
            if (cutInto(SafePath.resolve(backup, path), chunkings, existing)) {
                read.incrementAndGet();
            }
        });
        Path kept = Layout.backupDir(workspace, tag, targetId);
        List<Path> keptCopies;
        try (var walk = Files.walk(kept)) {
            keptCopies = walk.peek(path -> PathGuard.require(workspace, path))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList();
        } catch (IOException e) {
            return new Reuse(existing, read.get()); // a kept directory that cannot be listed only costs reuse
        }
        Parallel.forEach(keptCopies, concurrency, copy -> {
            String path = kept.relativize(copy).toString().replace('\\', '/');
            if (cutInto(copy, chunkingsFor(path, target, tables, targetChunkings), existing)) {
                read.incrementAndGet();
            }
        });
        return new Reuse(existing, read.get());
    }

    /** Splits one local file with every parameter given, adding the chunks found to {@code into}. */
    private static boolean cutInto(Path file, List<Chunking> chunkings, Map<String, Local> into) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        Map<String, Local> found = new HashMap<>();
        try {
            for (Chunking chunking : chunkings) {
                Chunker.split(file, chunking, cut -> found.putIfAbsent(cut.hash(), new Local(file, cut)));
            }
        } catch (UserError e) {
            return false; // a copy that cannot be read is as good as no copy at all
        }
        for (Map.Entry<String, Local> chunk : found.entrySet()) {
            into.putIfAbsent(chunk.getKey(), chunk.getValue());
        }
        return true;
    }

    /** The parameters the target table of one path records, or every one the run's target tables use. */
    private static List<Chunking> chunkingsFor(String path, Manifest target, Map<String, ChunkTable> tables,
            Set<Chunking> all) {
        FileEntry entry = target.files().get(path);
        ChunkTable table = entry == null ? null : tables.get(entry.indexId());
        return table == null ? List.copyOf(all) : List.of(table.chunking());
    }
}

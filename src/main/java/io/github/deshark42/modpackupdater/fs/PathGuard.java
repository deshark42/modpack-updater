package io.github.deshark42.modpackupdater.fs;

import io.github.deshark42.modpackupdater.UserError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Rejects filesystem redirections below a caller-selected root, including Windows junctions. */
public final class PathGuard {

    private final Path base;
    private final Path realBase;

    private PathGuard(Path base, Path realBase) {
        this.base = base;
        this.realBase = realBase;
    }

    /**
     * Captures an existing, caller-selected root for one batch of filesystem operations. Only the
     * trusted root's real path is reused: descendants are checked afresh on every require call.
     * Create a new guard for the next batch, rather than retaining it across commands.
     */
    public static PathGuard at(Path root) {
        Path base = root.toAbsolutePath().normalize();
        try {
            return new PathGuard(base, base.toRealPath());
        } catch (IOException e) {
            throw new UserError("cannot check filesystem root " + root + ": " + e.getMessage(), e);
        }
    }

    public Path require(Path path) {
        inspect(base, path, realBase, null);
        return path;
    }

    /**
     * Checks one read-only set of paths, sharing metadata only during this invocation. Returns the
     * attributes of fully existing requested paths, keyed by absolute normalized path; missing paths
     * and paths blocked by an ordinary file have no entry. Do not use this snapshot across mutations.
     * Ordinary require calls and subsequent inspections still check descendants afresh.
     */
    public Map<Path, BasicFileAttributes> inspectAll(Iterable<Path> paths) {
        Inspection inspection = new Inspection();
        Map<Path, BasicFileAttributes> result = new HashMap<>();
        for (Path path : paths) {
            BasicFileAttributes attributes = inspect(base, path, realBase, inspection);
            if (attributes != null) {
                result.put(path.toAbsolutePath().normalize(), attributes);
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Checks existing components without following links below {@code root}. Missing components may
     * be created later. The root itself is trusted (and may be a user-selected link); links below it
     * are rejected even when they point inside it, since they can alias the updater's own state.
     * This is not a lock against another process concurrently replacing checked components.
     */
    public static Path require(Path root, Path path) {
        Path base = root.toAbsolutePath().normalize();
        inspect(base, path, null, null);
        return path;
    }

    private static BasicFileAttributes inspect(Path base, Path path, Path realBase, Inspection inspection) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(base)) {
            throw new UserError("path escapes its filesystem root: " + path);
        }
        if (absolute.equals(base) && inspection == null) {
            return null;
        }
        try {
            if (absolute.equals(base)) {
                return Files.readAttributes(base, BasicFileAttributes.class); // The selected root may be a link.
            }
            if (realBase == null) {
                try {
                    realBase = base.toRealPath();
                } catch (NoSuchFileException e) {
                    return null; // a new workspace has no existing descendants yet
                }
            }
            Path current = base;
            Path existing = base;
            BasicFileAttributes deepest = null;
            for (Path part : base.relativize(absolute)) {
                current = current.resolve(part);
                BasicFileAttributes attributes;
                if (inspection != null && inspection.attributes.containsKey(current)) {
                    attributes = inspection.attributes.get(current);
                } else {
                    try {
                        attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    } catch (NoSuchFileException e) {
                        attributes = null;
                    }
                    if (inspection != null) {
                        inspection.attributes.put(current, attributes);
                    }
                }
                if (attributes == null) {
                    break;
                }
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw new UserError("refusing a filesystem link or alias below the workspace: " + current);
                }
                existing = current;
                deepest = attributes;
                if (!attributes.isDirectory()) {
                    break; // an ordinary file may be replaced with a directory during apply
                }
            }
            // Resolve at most one existing directory per path, rather than every leaf: an alias in an
            // ancestor (native spelling, link or junction) changes the deepest directory's real path,
            // while respelling a file leaf (8.3 short name, trailing dot or space, letter case) keeps
            // it in the same directory. The no-follow attribute checks above still reject links,
            // junctions and device names anywhere in the path, including through such a leaf alias.
            if (!existing.equals(base)) {
                Path resolved = deepest.isDirectory() ? existing : existing.getParent();
                if (inspection == null || !inspection.realPaths.contains(resolved)) {
                    Path expected = realBase.resolve(base.relativize(resolved));
                    if (!resolved.toRealPath().equals(expected)) {
                        throw new UserError("refusing a filesystem link or alias below the workspace: " + resolved);
                    }
                    if (inspection != null) {
                        inspection.realPaths.add(resolved);
                    }
                }
            }
            return absolute.equals(existing) ? deepest : null;
        } catch (IOException e) {
            throw new UserError("cannot check filesystem path " + path + ": " + e.getMessage(), e);
        }
    }

    private static final class Inspection {
        private final Map<Path, BasicFileAttributes> attributes = new HashMap<>();
        private final Set<Path> realPaths = new HashSet<>();
    }
}

package io.github.deshark42.modpackupdater.fs;

import io.github.deshark42.modpackupdater.UserError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

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
        return require(base, path, realBase);
    }

    /**
     * Checks existing components without following links below {@code root}. Missing components may
     * be created later. The root itself is trusted (and may be a user-selected link); links below it
     * are rejected even when they point inside it, since they can alias the updater's own state.
     * This is not a lock against another process concurrently replacing checked components.
     */
    public static Path require(Path root, Path path) {
        Path base = root.toAbsolutePath().normalize();
        return require(base, path, null);
    }

    private static Path require(Path base, Path path, Path realBase) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(base)) {
            throw new UserError("path escapes its filesystem root: " + path);
        }
        if (absolute.equals(base)) {
            return path;
        }
        try {
            if (realBase == null) {
                try {
                    realBase = base.toRealPath();
                } catch (NoSuchFileException e) {
                    return path; // a new workspace has no existing descendants yet
                }
            }
            Path current = base;
            Path existing = base;
            for (Path part : base.relativize(absolute)) {
                current = current.resolve(part);
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException e) {
                    break;
                }
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw new UserError("refusing a filesystem link or alias below the workspace: " + current);
                }
                existing = current;
                if (!attributes.isDirectory()) {
                    break; // an ordinary file may be replaced with a directory during apply
                }
            }
            // Resolve the deepest existing path once, rather than resolving every prefix (each
            // of which walks its ancestors again on Windows). Attribute checks above still reject
            // every link, including internal and dangling links; this also catches native aliases.
            Path expected = realBase.resolve(base.relativize(existing));
            if (!existing.equals(base) && !existing.toRealPath().equals(expected)) {
                throw new UserError("refusing a filesystem link or alias below the workspace: " + existing);
            }
            return path;
        } catch (IOException e) {
            throw new UserError("cannot check filesystem path " + path + ": " + e.getMessage(), e);
        }
    }
}

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

    private PathGuard() {
    }

    /**
     * Checks existing components without following links below {@code root}. Missing components may
     * be created later. The root itself is trusted (and may be a user-selected link); links below it
     * are rejected even when they point inside it, since they can alias the updater's own state.
     * This is not a lock against another process concurrently replacing checked components.
     */
    public static Path require(Path root, Path path) {
        Path base = root.toAbsolutePath().normalize();
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(base)) {
            throw new UserError("path escapes its filesystem root: " + path);
        }
        if (absolute.equals(base)) {
            return path;
        }
        try {
            Path expected;
            try {
                expected = base.toRealPath();
            } catch (NoSuchFileException e) {
                return path; // a new workspace has no existing descendants yet
            }
            Path current = base;
            for (Path part : base.relativize(absolute)) {
                current = current.resolve(part);
                expected = expected.resolve(part);
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException e) {
                    return path;
                }
                // isOther catches reparse points on Windows providers; real paths also catch
                // junctions/aliases that a provider reports as ordinary directories.
                if (attributes.isSymbolicLink() || attributes.isOther() || !current.toRealPath().equals(expected)) {
                    throw new UserError("refusing a filesystem link or alias below the workspace: " + current);
                }
                if (!attributes.isDirectory()) {
                    return path; // an ordinary file may be replaced with a directory during apply
                }
            }
            return path;
        } catch (IOException e) {
            throw new UserError("cannot check filesystem path " + path + ": " + e.getMessage(), e);
        }
    }
}

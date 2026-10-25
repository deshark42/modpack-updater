package io.github.deshark42.modpackupdater.model;

import io.github.deshark42.modpackupdater.UserError;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Manifest paths are relative and stay inside the workspace. The separator and colon checks keep a
 * manifest written on one platform from meaning something else on another.
 */
public final class SafePath {

    private SafePath() {
    }

    public static String require(String relative) {
        if (relative == null || relative.isEmpty()) {
            throw new UserError("a manifest path must not be empty");
        }
        if (relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0 || relative.startsWith("/")) {
            throw new UserError("manifest path is not a plain relative path: " + relative);
        }
        requireNotReserved(relative);
        String normalized;
        try {
            normalized = Path.of(relative).normalize().toString().replace('\\', '/');
        } catch (InvalidPathException e) {
            throw new UserError("invalid manifest path: " + relative, e);
        }
        if (normalized.isEmpty() || normalized.equals(".") || normalized.equals("..") || normalized.startsWith("../")) {
            throw new UserError("manifest path escapes the workspace: " + relative);
        }
        requireNotReserved(normalized);
        return normalized;
    }

    private static void requireNotReserved(String relative) {
        String first = relative.split("/", 2)[0];
        // Windows ignores case and trailing dots/spaces when opening ordinary paths. Reserve the
        // same names on every platform so a release published on Linux stays safe on Windows.
        int end = first.length();
        while (end > 0 && (first.charAt(end - 1) == '.' || first.charAt(end - 1) == ' ')) {
            end--;
        }
        if (first.substring(0, end).equalsIgnoreCase(Layout.STATE_DIR)) {
            throw new UserError("manifest path uses the updater's reserved state directory: " + relative);
        }
    }

    public static Path resolve(Path root, String relative) {
        return root.resolve(require(relative)).normalize();
    }
}

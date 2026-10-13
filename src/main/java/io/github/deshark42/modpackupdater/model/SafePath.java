package io.github.deshark42.modpackupdater.model;

import io.github.deshark42.modpackupdater.UserError;
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
        String normalized = Path.of(relative).normalize().toString().replace('\\', '/');
        if (normalized.isEmpty() || normalized.equals(".") || normalized.equals("..") || normalized.startsWith("../")) {
            throw new UserError("manifest path escapes the workspace: " + relative);
        }
        return normalized;
    }

    public static Path resolve(Path root, String relative) {
        return root.resolve(require(relative)).normalize();
    }
}

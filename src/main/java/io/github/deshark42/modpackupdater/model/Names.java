package io.github.deshark42.modpackupdater.model;

import io.github.deshark42.modpackupdater.UserError;
import java.util.regex.Pattern;

/**
 * Tags and release ids end up in object keys and file names. Restricting them to a portable
 * character set at both ends keeps them from walking out of the bucket prefix or the state directory.
 */
public final class Names {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final int MAX_LENGTH = 120;

    private Names() {
    }

    public static String requireTag(String tag) {
        return require(tag, "tag");
    }

    public static String requireReleaseId(String releaseId) {
        return require(releaseId, "release id");
    }

    private static String require(String value, String what) {
        if (value == null || value.isEmpty()) {
            throw new UserError("the " + what + " must not be empty");
        }
        if (value.length() > MAX_LENGTH) {
            throw new UserError("the " + what + " is longer than " + MAX_LENGTH + " characters: " + value);
        }
        if (!ALLOWED.matcher(value).matches()) {
            throw new UserError("invalid " + what + " \"" + value
                    + "\": use letters, digits, dot, dash or underscore, starting with a letter or digit");
        }
        return value;
    }
}

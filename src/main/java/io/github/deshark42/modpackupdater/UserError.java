package io.github.deshark42.modpackupdater;

/** Anything the user can fix: bad arguments, bad config, bad remote data, missing files. */
public class UserError extends RuntimeException {
    public UserError(String message) {
        super(message);
    }

    public UserError(String message, Throwable cause) {
        super(message, cause);
    }
}

package io.github.deshark42.modpackupdater.model;

import io.github.deshark42.modpackupdater.UserError;

/** Binds a verified manifest to the tag and release requested by the subscriber. */
public final class ReleaseIdentity {
    private ReleaseIdentity() {
    }

    public static void require(Manifest target, String tag, String releaseId) {
        if (!tag.equals(target.tag()) || !releaseId.equals(target.releaseId())) {
            throw new UserError("signed manifest identity does not match requested tag " + tag + " release " + releaseId);
        }
    }
}

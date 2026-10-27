package io.github.deshark42.modpackupdater.fs;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.Pointer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads local release state without parsing an already-current manifest. */
public record AppliedRelease(String releaseId, Manifest manifest) {
    private static final Logger LOG = LoggerFactory.getLogger(AppliedRelease.class);

    public static AppliedRelease read(Path workspace, String tag, String targetId) {
        LOG.debug("tag {}: reading local release pointer", tag);
        Path currentFile = Layout.currentFile(workspace, tag);
        if (!Files.exists(currentFile)) {
            return new AppliedRelease(null, null);
        }
        String applied = Pointer.read(currentFile).releaseId();
        Path appliedManifest = Layout.releaseFile(workspace, tag, applied);
        if (!Files.isRegularFile(appliedManifest)) {
            throw new UserError("cannot tell which files are already applied: " + appliedManifest
                    + " is missing; restore it or delete " + currentFile);
        }
        if (targetId.equals(applied)) {
            return new AppliedRelease(applied, null);
        }
        LOG.debug("tag {}: reading local manifest for release {}", tag, applied);
        return new AppliedRelease(applied, Manifest.read(appliedManifest));
    }
}

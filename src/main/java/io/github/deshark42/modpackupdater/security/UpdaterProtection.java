package io.github.deshark42.modpackupdater.security;

import io.github.deshark42.modpackupdater.Main;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.SafePath;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.CodeSource;

/** Separates release payload paths from the code that enforces the update policy. */
public final class UpdaterProtection {

    private final Path workspace;
    private final Path realWorkspace;
    private final Path updater;

    public UpdaterProtection(Path workspace, Path updater) {
        this.workspace = workspace.toAbsolutePath().normalize();
        try {
            this.realWorkspace = this.workspace.toRealPath();
            this.updater = updater.toRealPath();
        } catch (IOException e) {
            throw new UserError("cannot locate the running updater or workspace for payload protection", e);
        }
    }

    /** Uses the loaded entry point, not a filename convention or a release-supplied setting. */
    public static Path runningUpdater() {
        try {
            CodeSource source = Main.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null
                    || !"file".equalsIgnoreCase(source.getLocation().getProtocol())) {
                throw new UserError("cannot locate the running updater for payload protection");
            }
            return Path.of(source.getLocation().toURI());
        } catch (URISyntaxException | IllegalArgumentException | SecurityException e) {
            throw new UserError("cannot locate the running updater for payload protection", e);
        }
    }

    public void require(Manifest manifest) {
        for (String relative : manifest.files().keySet()) {
            Path file = SafePath.resolve(workspace, relative);
            Path realFile = realWorkspace.resolve(workspace.relativize(file.toAbsolutePath().normalize()));
            // Moving an ancestor moves the updater too; a classes directory is itself executable code.
            if (realFile.startsWith(updater) || updater.startsWith(realFile)) {
                throw conflict(relative);
            }
            try {
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(file, updater)) {
                    throw conflict(relative);
                }
            } catch (IOException e) {
                throw new UserError("cannot check release path against the running updater: " + relative, e);
            }
        }
    }

    private static UserError conflict(String relative) {
        return new UserError("release path conflicts with the running updater: " + relative);
    }
}

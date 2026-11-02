package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import io.github.deshark42.modpackupdater.fs.PathGuard;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.json.Json;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.ReleaseIdentity;
import io.github.deshark42.modpackupdater.security.ReleaseSigning;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.PublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Disposable signed copies of successful publications, separate from applied subscriber state. */
final class PublishedReleaseCache {
    private static final Logger LOG = LoggerFactory.getLogger(PublishedReleaseCache.class);

    private PublishedReleaseCache() {
    }

    static Manifest read(Path workspace, Config config, String tag, String releaseId, PublicKey trustedKey) {
        try {
            Path manifestFile = manifestFile(workspace, config, tag, releaseId);
            Path signatureFile = signatureFile(workspace, config, tag, releaseId);
            if (!Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(signatureFile, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(manifestFile);
            byte[] signature = Files.readAllBytes(signatureFile);
            ReleaseSigning.verify(bytes, signature, trustedKey);
            Manifest manifest = Manifest.parse(new String(bytes, StandardCharsets.UTF_8), manifestFile.toString());
            ReleaseIdentity.require(manifest, tag, releaseId);
            LOG.info("using local release {} of tag {} as the publish baseline", releaseId, tag);
            return manifest;
        } catch (IOException | UserError | Json.JsonException e) {
            LOG.warn("cannot use local release {} of tag {}; reading it from object storage: {}",
                    releaseId, tag, e.getMessage());
            return null;
        }
    }

    static void remember(Path workspace, Config config, String tag, String releaseId, byte[] manifest, byte[] signature) {
        try {
            Path manifestFile = manifestFile(workspace, config, tag, releaseId);
            Path signatureFile = signatureFile(workspace, config, tag, releaseId);
            write(workspace, manifestFile, manifest);
            write(workspace, signatureFile, signature);
        } catch (IOException | UserError e) {
            LOG.warn("cannot cache local release {} of tag {}; the publication succeeded: {}",
                    releaseId, tag, e.getMessage());
        }
    }

    static Path manifestFile(Path workspace, Config config, String tag, String releaseId) {
        return Layout.publishedReleaseFile(workspace, location(config), tag, releaseId);
    }

    static Path signatureFile(Path workspace, Config config, String tag, String releaseId) {
        return Layout.publishedReleaseSignatureFile(workspace, location(config), tag, releaseId);
    }

    private static String location(Config config) {
        return Sha256.ofBytes((config.endpoint() + "\n" + config.bucket() + "\n" + config.prefix())
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Path workspace, Path file, byte[] bytes) throws IOException {
        PathGuard.require(workspace, file);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, bytes);
            AtomicFile.move(temp, PathGuard.require(workspace, file));
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}

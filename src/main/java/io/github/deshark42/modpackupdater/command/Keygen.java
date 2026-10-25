package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.PathGuard;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.security.ReleaseSigning;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Generates local keys without contacting storage or replacing an existing trust identity. */
public final class Keygen {
    private static final Logger LOG = LoggerFactory.getLogger(Keygen.class);

    private Keygen() {
    }

    public static void run(Path workspace) {
        Path privateFile = PathGuard.require(workspace, workspace.resolve(Layout.DEFAULT_PRIVATE_KEY));
        Path publicFile = PathGuard.require(workspace, workspace.resolve(Layout.DEFAULT_PUBLIC_KEY));
        if (Files.exists(privateFile, LinkOption.NOFOLLOW_LINKS) || Files.exists(publicFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new UserError("a signing key already exists; refusing to replace signing keys in "
                    + privateFile.getParent());
        }
        KeyPair pair = ReleaseSigning.generate();
        boolean createdPrivate = false;
        try {
            Files.createDirectories(privateFile.getParent());
            FileAttribute<?>[] permissions = Files.getFileStore(privateFile.getParent())
                    .supportsFileAttributeView("posix")
                    ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                    : new FileAttribute<?>[0];
            Files.createFile(privateFile, permissions);
            createdPrivate = true;
            Files.writeString(privateFile, ReleaseSigning.pem("PRIVATE KEY", pair.getPrivate().getEncoded()),
                    StandardCharsets.US_ASCII, StandardOpenOption.WRITE);
            Files.writeString(publicFile, ReleaseSigning.pem("PUBLIC KEY", pair.getPublic().getEncoded()),
                    StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            if (createdPrivate) {
                try {
                    Files.delete(privateFile);
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw new UserError("cannot create signing keys in " + privateFile.getParent() + ": " + e.getMessage(), e);
        }
        LOG.info("created private signing key {}", privateFile);
        LOG.info("created public verification key {}", publicFile);
        LOG.info("public key SHA-256 fingerprint: {}", Sha256.ofBytes(pair.getPublic().getEncoded()));
        LOG.info("give subscribers only the public key; keep the private key on the publisher and back it up");
    }
}

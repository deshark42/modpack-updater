package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.security.ReleaseSigning;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.Signature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock(TestLogs.RESOURCE)
class KeygenTest {
    @Test
    void keygenCreatesMatchingKeysInTheProtectedStateDirectory(@TempDir Path root)
            throws IOException, GeneralSecurityException {
        try (TestLogs logs = new TestLogs()) {
            logs.begin(root);

            Keygen.run(root);

            byte[] message = TestFiles.bytes("a release manifest");
            byte[] signature = ReleaseSigning.sign(message, ReleaseSigning.privateKey(root.resolve(Layout.DEFAULT_PRIVATE_KEY)));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(ReleaseSigning.publicKey(root.resolve(Layout.DEFAULT_PUBLIC_KEY)));
            verifier.update(message);
            assertTrue(verifier.verify(signature), "the persisted public key verifies the persisted private key signature");
            if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
                assertEquals(PosixFilePermissions.fromString("rw-------"),
                        Files.getPosixFilePermissions(root.resolve(Layout.DEFAULT_PRIVATE_KEY)));
            }
            assertFalse(Files.exists(Layout.configFile(root)), "key generation does not need or rewrite storage config");
        }
    }

    @Test
    void anExistingPublicKeyPreventsCreatingOrReplacingAnySigningKey(@TempDir Path root) throws IOException {
        try (TestLogs logs = new TestLogs()) {
            logs.begin(root);

            TestFiles.write(root, Layout.DEFAULT_PUBLIC_KEY, "existing public key");

            UserError error = assertThrows(UserError.class, () -> Keygen.run(root));

            assertTrue(error.getMessage().contains("refusing to replace signing keys"), error.getMessage());
            assertEquals("existing public key", Files.readString(root.resolve(Layout.DEFAULT_PUBLIC_KEY)));
            assertFalse(Files.exists(root.resolve(Layout.DEFAULT_PRIVATE_KEY)));
            assertFalse(Files.exists(Layout.configFile(root)));
        }
    }
}

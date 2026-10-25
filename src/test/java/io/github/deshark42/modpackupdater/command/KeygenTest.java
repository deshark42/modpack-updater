package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.security.ReleaseSigning;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KeygenTest {
    @Test
    void createsMatchingKeysInTheProtectedStateDirectory(@TempDir Path root) {
        Keygen.run(root);

        byte[] message = TestFiles.bytes("a release manifest");
        byte[] signature = ReleaseSigning.sign(message, ReleaseSigning.privateKey(root.resolve(Layout.DEFAULT_PRIVATE_KEY)));
        ReleaseSigning.verify(message, signature, ReleaseSigning.publicKey(root.resolve(Layout.DEFAULT_PUBLIC_KEY)));
        assertEquals(ReleaseSigning.SIGNATURE_BYTES, signature.length);
        assertFalse(Files.exists(Layout.configFile(root)), "key generation does not need or rewrite storage config");
    }

    @ParameterizedTest(name = "existing {0} keys are preserved")
    @ValueSource(strings = {"public", "private", "both"})
    void neverReplacesAnExistingSigningIdentity(String which, @TempDir Path root) throws IOException {
        if (!which.equals("private")) {
            TestFiles.write(root, Layout.DEFAULT_PUBLIC_KEY, "existing public key");
        }
        if (!which.equals("public")) {
            TestFiles.write(root, Layout.DEFAULT_PRIVATE_KEY, "existing private key");
        }

        UserError error = assertThrows(UserError.class, () -> Keygen.run(root));

        assertTrue(error.getMessage().contains("refusing to replace signing keys"), error.getMessage());
        if (!which.equals("private")) {
            assertEquals("existing public key", Files.readString(root.resolve(Layout.DEFAULT_PUBLIC_KEY)));
        } else {
            assertFalse(Files.exists(root.resolve(Layout.DEFAULT_PUBLIC_KEY)));
        }
        if (!which.equals("public")) {
            assertEquals("existing private key", Files.readString(root.resolve(Layout.DEFAULT_PRIVATE_KEY)));
        } else {
            assertFalse(Files.exists(root.resolve(Layout.DEFAULT_PRIVATE_KEY)));
        }
    }

    @Test
    void createsThePrivateKeyWithOwnerOnlyPosixPermissions(@TempDir Path root) throws IOException {
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"), "POSIX permissions are platform-specific");
        Keygen.run(root);
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(root.resolve(Layout.DEFAULT_PRIVATE_KEY)));
    }
}

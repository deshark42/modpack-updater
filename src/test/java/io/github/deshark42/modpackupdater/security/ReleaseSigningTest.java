package io.github.deshark42.modpackupdater.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestSigning;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReleaseSigningTest {
    // RFC 8032 section 7.1 test 1 signs an empty message with the fixture key.
    private static final byte[] EMPTY_SIGNATURE = HexFormat.of().parseHex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
                    + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");

    @Test
    void matchesTheIndependentRfc8032SignatureVector(@TempDir Path root) throws IOException {
        TestSigning.writeKeys(root);
        byte[] signed = ReleaseSigning.sign(new byte[0], ReleaseSigning.privateKey(root.resolve(Layout.DEFAULT_PRIVATE_KEY)));
        assertArrayEquals(EMPTY_SIGNATURE, signed);
        ReleaseSigning.verify(new byte[0], EMPTY_SIGNATURE, ReleaseSigning.publicKey(root.resolve(Layout.DEFAULT_PUBLIC_KEY)));
    }

    @Test
    void rejectsAChangedMessageEvenWhenJsonMeaningWouldBeUnchanged(@TempDir Path root) throws IOException {
        TestSigning.writeKeys(root);
        byte[] original = TestFiles.bytes("{\"files\":{}}");
        byte[] signed = TestSigning.sign(original);
        PublicKey key = ReleaseSigning.publicKey(root.resolve(Layout.DEFAULT_PUBLIC_KEY));

        UserError error = assertThrows(UserError.class,
                () -> ReleaseSigning.verify(TestFiles.bytes("{ \"files\":{} }"), signed, key));

        assertTrue(error.getMessage().contains("signature does not match"), error.getMessage());
        assertArrayEquals(TestSigning.sign(original), signed);
    }

    @ParameterizedTest(name = "a signature of {0} bytes is rejected")
    @ValueSource(ints = {0, 1, 63, 65, 128})
    void rejectsSignaturesOfTheWrongLength(int length, @TempDir Path root) throws IOException {
        TestSigning.writeKeys(root);
        PublicKey key = ReleaseSigning.publicKey(root.resolve(Layout.DEFAULT_PUBLIC_KEY));
        UserError error = assertThrows(UserError.class, () -> ReleaseSigning.verify(new byte[0], new byte[length], key));
        assertTrue(error.getMessage().contains("expected 64 Ed25519 bytes"), error.getMessage());
    }

    @ParameterizedTest(name = "malformed PEM {0} is rejected")
    @ValueSource(strings = {"wrong label", "bad base64", "bad DER"})
    void rejectsMalformedPublicKeys(String kind, @TempDir Path root) throws IOException {
        Path file = root.resolve("key.pem");
        String content = switch (kind) {
            case "wrong label" -> ReleaseSigning.pem("PRIVATE KEY", TestSigning.PRIVATE_DER);
            case "bad base64" -> "-----BEGIN PUBLIC KEY-----\n!\n-----END PUBLIC KEY-----\n";
            default -> ReleaseSigning.pem("PUBLIC KEY", new byte[]{1, 2, 3});
        };
        Files.writeString(file, content);
        UserError error = assertThrows(UserError.class, () -> ReleaseSigning.publicKey(file));
        assertTrue(error.getMessage().contains("key") || error.getMessage().contains("PUBLIC KEY"), error.getMessage());
        assertEquals(content, Files.readString(file));
    }

    @Test
    void rejectsAMalformedPrivateKeyWithoutChangingIt(@TempDir Path root) throws IOException {
        Path file = root.resolve("private.pem");
        String content = ReleaseSigning.pem("PRIVATE KEY", new byte[]{1, 2, 3});
        Files.writeString(file, content);
        UserError error = assertThrows(UserError.class, () -> ReleaseSigning.privateKey(file));
        assertTrue(error.getMessage().contains("not an Ed25519 PKCS#8 private key"), error.getMessage());
        assertEquals(content, Files.readString(file));
    }
}

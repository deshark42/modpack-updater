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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReleaseSigningTest {
    // RFC 8032 section 7.1 test 1 signs an empty message with the fixture key.
    private static byte[] emptySignature() {
        return HexFormat.of().parseHex(
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
                        + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
    }

    @Test
    void matchesTheIndependentRfc8032SignatureVector(@TempDir Path root) throws IOException {
        TestSigning.writeKeys(root);
        var key = ReleaseSigning.privateKey(root.resolve(Layout.DEFAULT_PRIVATE_KEY));

        byte[] signed = ReleaseSigning.sign(new byte[0], key);

        assertArrayEquals(emptySignature(), signed);
    }

    @Test
    void rejectsAChangedMessageEvenWhenJsonMeaningWouldBeUnchanged(@TempDir Path root) throws IOException {
        TestSigning.writeKeys(root);
        byte[] signed = TestSigning.sign(TestFiles.bytes("{\"files\":{}}"));
        byte[] unchangedSignature = signed.clone();
        byte[] changed = TestFiles.bytes("{ \"files\":{} }");
        PublicKey key = ReleaseSigning.publicKey(root.resolve(Layout.DEFAULT_PUBLIC_KEY));

        UserError error = assertThrows(UserError.class, () -> ReleaseSigning.verify(changed, signed, key));

        assertTrue(error.getMessage().contains("signature does not match"), error.getMessage());
        assertArrayEquals(unchangedSignature, signed);
        assertArrayEquals(TestFiles.bytes("{ \"files\":{} }"), changed);
    }

    @Test
    void rejectsAChangedSignatureOfTheCorrectLength(@TempDir Path root) throws IOException {
        TestSigning.writeKeys(root);
        byte[] signed = emptySignature();
        signed[signed.length - 1] ^= 1;
        byte[] unchangedSignature = signed.clone();
        PublicKey key = ReleaseSigning.publicKey(root.resolve(Layout.DEFAULT_PUBLIC_KEY));

        UserError error = assertThrows(UserError.class, () -> ReleaseSigning.verify(new byte[0], signed, key));

        assertTrue(error.getMessage().contains("signature does not match"), error.getMessage());
        assertArrayEquals(unchangedSignature, signed);
    }

    @Test
    void rejectsASignatureFromAnotherValidEd25519Key(@TempDir Path root) throws IOException {
        // RFC 8032 section 7.1 test 2 supplies a distinct valid public key.
        byte[] encoded = HexFormat.of().parseHex("302a300506032b6570032100"
                + "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c");
        Path file = root.resolve("other.pem");
        String pem = ReleaseSigning.pem("PUBLIC KEY", encoded);
        Files.writeString(file, pem, StandardCharsets.US_ASCII);
        PublicKey key = ReleaseSigning.publicKey(file);
        byte[] signed = emptySignature();

        UserError error = assertThrows(UserError.class, () -> ReleaseSigning.verify(new byte[0], signed, key));

        assertTrue(error.getMessage().contains("signature does not match"), error.getMessage());
        assertArrayEquals(emptySignature(), signed);
        assertEquals(pem, Files.readString(file, StandardCharsets.US_ASCII));
    }

    @ParameterizedTest(name = "a signature of {0} bytes is rejected")
    @ValueSource(ints = {0, 1, 63, 65, 128})
    void rejectsSignaturesOfTheWrongLength(int length, @TempDir Path root) throws IOException {
        TestSigning.writeKeys(root);
        PublicKey key = ReleaseSigning.publicKey(root.resolve(Layout.DEFAULT_PUBLIC_KEY));
        byte[] signed = new byte[length];

        UserError error = assertThrows(UserError.class, () -> ReleaseSigning.verify(new byte[0], signed, key));

        assertTrue(error.getMessage().contains("expected " + ReleaseSigning.SIGNATURE_BYTES + " Ed25519 bytes"),
                error.getMessage());
        assertArrayEquals(new byte[length], signed);
    }

    private static Stream<Arguments> malformedPublicKeys() {
        return Stream.of(
                Arguments.of("wrong label", ReleaseSigning.pem("PRIVATE KEY", TestSigning.privateDer()),
                        "expected a PEM PUBLIC KEY"),
                Arguments.of("bad base64", "-----BEGIN PUBLIC KEY-----\n!\n-----END PUBLIC KEY-----\n",
                        "invalid PEM signing key"),
                Arguments.of("bad DER", ReleaseSigning.pem("PUBLIC KEY", new byte[]{1, 2, 3}),
                        "not an Ed25519 public key"));
    }

    @ParameterizedTest(name = "malformed PEM {0} is rejected")
    @MethodSource("malformedPublicKeys")
    void rejectsMalformedPublicKeys(String kind, String content, String reason, @TempDir Path root) throws IOException {
        Path file = root.resolve("key.pem");
        Files.writeString(file, content, StandardCharsets.US_ASCII);

        UserError error = assertThrows(UserError.class, () -> ReleaseSigning.publicKey(file));

        assertTrue(error.getMessage().contains(reason), error.getMessage());
        assertEquals(content, Files.readString(file, StandardCharsets.US_ASCII));
    }

    @Test
    void rejectsAMalformedPrivateKeyWithoutChangingIt(@TempDir Path root) throws IOException {
        Path file = root.resolve("private.pem");
        String content = ReleaseSigning.pem("PRIVATE KEY", new byte[]{1, 2, 3});
        Files.writeString(file, content, StandardCharsets.US_ASCII);

        UserError error = assertThrows(UserError.class, () -> ReleaseSigning.privateKey(file));

        assertTrue(error.getMessage().contains("not an Ed25519 PKCS#8 private key"), error.getMessage());
        assertEquals(content, Files.readString(file, StandardCharsets.US_ASCII));
    }
}

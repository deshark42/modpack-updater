package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestSigning;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import io.github.deshark42.modpackupdater.model.Pointer;
import io.github.deshark42.modpackupdater.security.ReleaseSigning;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SignedReleaseTest {
    private static final String TAG = "client";

    private static String release(String id) {
        return TestWorkspace.bucketPath(ObjectKeys.release(TAG, id));
    }

    private static String signature(String id) {
        return TestWorkspace.bucketPath(ObjectKeys.signature(TAG, id));
    }

    private static String latest() {
        return TestWorkspace.bucketPath(ObjectKeys.latest(TAG));
    }

    @Test
    void signsBeforeAdvancingLatestAndUpdatesWithOnlyThePublicKey(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            TestWorkspace.base(publisher, TAG, "mods/a.jar", "A");
            Publish.run(publisher, TAG, "1");
            List<String> requests = s3.requests();
            assertTrue(requests.indexOf("PUT " + release("1")) < requests.indexOf("PUT " + signature("1")));
            assertTrue(requests.indexOf("PUT " + signature("1")) < requests.indexOf("PUT " + latest()));
            ReleaseSigning.verify(TestFiles.bytes(s3.text(release("1"))), s3.object(signature("1")),
                    ReleaseSigning.publicKey(publisher.resolve(Layout.DEFAULT_PUBLIC_KEY)));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Files.delete(player.resolve(Layout.DEFAULT_PRIVATE_KEY));

            Update.run(player, TAG, null);

            assertEquals("A", Files.readString(player.resolve("mods/a.jar")));
            assertEquals("1", Pointer.read(Layout.currentFile(player, TAG)).releaseId());
            assertFalse(Files.exists(player.resolve(Layout.DEFAULT_PRIVATE_KEY)));
        }
    }

    @ParameterizedTest(name = "update rejects {0} before requesting content")
    @ValueSource(strings = {"changed manifest", "changed signature", "missing signature",
            "different public key", "different tag", "different release"})
    void rejectsUnauthenticatedOrSubstitutedReleasesWithoutApplyingAnything(String attack, @TempDir Path root)
            throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            TestWorkspace.base(publisher, TAG, "mods/a.jar", "old");
            Publish.run(publisher, TAG, "1");
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Update.run(player, TAG, null);
            TestWorkspace.base(publisher, TAG, "mods/a.jar", "new");
            Publish.run(publisher, TAG, "2");
            String expected = "signature";
            switch (attack) {
                case "changed manifest" -> s3.put(release("2"), TestFiles.bytes("not even JSON"));
                case "changed signature" -> {
                    byte[] changed = s3.object(signature("2"));
                    changed[0] ^= 1;
                    s3.put(signature("2"), changed);
                }
                case "missing signature" -> {
                    s3.fail("GET", signature("2"), 404);
                    expected = "not found";
                }
                case "different public key" -> {
                    // RFC 8032 section 7.1 test 2 is another valid, deterministic Ed25519 key.
                    byte[] other = HexFormat.of().parseHex("302a300506032b6570032100"
                            + "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c");
                    Files.writeString(player.resolve(Layout.DEFAULT_PUBLIC_KEY), ReleaseSigning.pem("PUBLIC KEY", other));
                }
                default -> {
                    // A valid signature for another identity must not authorize the requested one.
                    String text = s3.text(release("2"));
                    text = attack.equals("different tag") ? text.replace("\"tag\":\"client\"", "\"tag\":\"server\"")
                            : text.replace("\"release_id\":\"2\"", "\"release_id\":\"another\"");
                    byte[] changed = TestFiles.bytes(text);
                    s3.put(release("2"), changed);
                    s3.put(signature("2"), TestSigning.sign(changed));
                    expected = "signed manifest identity";
                }
            }
            int before = s3.requests().size();

            UserError error = assertThrows(UserError.class, () -> Update.run(player, TAG, null));

            assertTrue(error.getMessage().contains(expected), error.getMessage());
            assertEquals("old", Files.readString(player.resolve("mods/a.jar")));
            assertEquals("1", Pointer.read(Layout.currentFile(player, TAG)).releaseId());
            assertFalse(Files.exists(Layout.releaseFile(player, TAG, "2")));
            assertFalse(Files.exists(Layout.backupsDir(player, TAG)));
            assertEquals(List.of("GET " + latest(), "GET " + release("2"), "GET " + signature("2")),
                    s3.requests().subList(before, s3.requests().size()), "no chunk table or chunk is requested");
        }
    }

    @Test
    void refusesToPublishWithoutAPrivateKeyBeforeContactingStorage(@TempDir Path publisher) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(publisher, s3);
            TestWorkspace.base(publisher, TAG, "mods/a.jar", "A");
            Files.delete(publisher.resolve(Layout.DEFAULT_PRIVATE_KEY));

            UserError error = assertThrows(UserError.class, () -> Publish.run(publisher, TAG, "1"));

            assertTrue(error.getMessage().contains("cannot read signing key"), error.getMessage());
            assertEquals(List.of(), s3.requests());
            assertEquals(0, s3.objectCount());
            assertFalse(Files.exists(Layout.knownBlobsFile(publisher)));
        }
    }

    @Test
    void refusesToUpdateWithoutALocalTrustedKeyBeforeContactingStorage(@TempDir Path player) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(player, s3);
            Files.delete(player.resolve(Layout.DEFAULT_PUBLIC_KEY));
            TestFiles.write(player, "mods/local.jar", "local");

            UserError error = assertThrows(UserError.class, () -> Update.run(player, TAG, null));

            assertTrue(error.getMessage().contains("cannot read signing key"), error.getMessage());
            assertEquals(List.of(), s3.requests());
            assertEquals("local", Files.readString(player.resolve("mods/local.jar")));
            assertFalse(Files.exists(Layout.currentFile(player, TAG)));
        }
    }

    @Test
    void leavesLatestUnchangedWhenUploadingTheSignatureFails(@TempDir Path publisher) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(publisher, s3);
            TestWorkspace.base(publisher, TAG, "mods/a.jar", "A");
            Publish.run(publisher, TAG, "1");
            s3.fail("PUT", signature("2"), 500);
            int before = s3.requests().size();

            UserError error = assertThrows(UserError.class, () -> Publish.run(publisher, TAG, "2"));

            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
            assertEquals("1", Pointer.parse(s3.text(latest()), "latest").releaseId());
            assertNull(s3.object(signature("2")));
            assertFalse(s3.requests().subList(before, s3.requests().size()).contains("PUT " + latest()));
        }
    }
}

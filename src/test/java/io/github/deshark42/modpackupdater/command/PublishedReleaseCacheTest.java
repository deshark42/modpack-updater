package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.TestSigning;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.json.Json;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.FileEntry;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import io.github.deshark42.modpackupdater.model.Pointer;
import io.github.deshark42.modpackupdater.security.ReleaseSigning;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ResourceLock(TestLogs.RESOURCE)
class PublishedReleaseCacheTest {
    private static final String TAG = "client";
    private static final String CHANNEL = "beta";
    private static final String OLD = "previous";
    private static final String NEXT = "next";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void aPublishedPairSuppliesOtherChannelsWithoutRemoteReleaseReadsOrSubscriberChanges(@TempDir Path workspace)
            throws Exception {
        try (FakeS3 s3 = new FakeS3(); FakeS3 cdn = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "payload.txt", "published payload");
            TestFiles.write(workspace, workspace.relativize(Layout.releaseFile(workspace, TAG, OLD)).toString(),
                    "subscriber manifest stays separate");
            TestFiles.write(workspace, workspace.relativize(Layout.currentFile(workspace, TAG)).toString(),
                    "subscriber current stays separate");
            logs.begin(workspace);
            Publish.run(workspace, TAG, OLD, 1, CHANNEL, CLOCK);
            Config config = Config.load(workspace);
            Path manifest = PublishedReleaseCache.manifestFile(workspace, config, TAG, OLD);
            Path signature = PublishedReleaseCache.signatureFile(workspace, config, TAG, OLD);
            byte[] published = TestFiles.bytes(s3.text(key(config, ObjectKeys.release(TAG, OLD))));
            assertArrayEquals(published, Files.readAllBytes(manifest));
            assertArrayEquals(s3.object(key(config, ObjectKeys.signature(TAG, OLD))), Files.readAllBytes(signature));
            assertEquals(ReleaseSigning.SIGNATURE_BYTES, Files.size(signature));
            String scope = Sha256.ofBytes(TestFiles.bytes(config.endpoint() + "\n" + config.bucket()
                    + "\n" + config.prefix()));
            assertEquals(Layout.tagDir(workspace, TAG).resolve("published-releases").resolve(scope)
                    .resolve(OLD + ".json"), manifest);
            assertEquals(manifest.resolveSibling(OLD + ".json.sig"), signature);
            assertEquals("subscriber manifest stays separate", Files.readString(Layout.releaseFile(workspace, TAG, OLD)));
            assertEquals("subscriber current stays separate", Files.readString(Layout.currentFile(workspace, TAG)));
            s3.put(key(config, ObjectKeys.channel(TAG, "stable")), TestFiles.bytes(new Pointer(OLD).toJson()));
            changeConfig(workspace, "read_endpoint", cdn.url());
            changeConfig(workspace, "prefix", TestWorkspace.PREFIX + "/");
            assertEquals(manifest, PublishedReleaseCache.manifestFile(workspace, Config.load(workspace), TAG, OLD));
            denyReleaseReads(s3, config, OLD);
            int requestsBefore = s3.requests().size();
            Map<String, String> before = disk(workspace);
            Clock later = Clock.fixed(Instant.parse("2036-10-20T00:00:00Z"), ZoneOffset.UTC);

            try (Publish.Prepared prepared = Publish.prepare(workspace, TAG, "stable", later)) {
                assertEquals(Manifest.parse(s3.text(key(config, ObjectKeys.release(TAG, OLD))), "published"),
                        prepared.baseline());
            }

            assertEquals(channelReads(config, "stable"), since(s3, requestsBefore));
            assertEquals(List.of(), cdn.requests());
            assertEquals(before, disk(workspace));
        }
    }

    @ParameterizedTest(name = "a changed {0} uses its own signed baseline")
    @ValueSource(strings = {"endpoint", "bucket", "prefix"})
    void anotherDestinationCannotReuseTheSameTagAndReleaseFromTheOldDestination(String field,
            @TempDir Path workspace) throws Exception {
        try (FakeS3 first = new FakeS3(); FakeS3 second = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, first);
            TestWorkspace.base(workspace, TAG, "payload.txt", "old destination");
            logs.begin(workspace);
            Publish.run(workspace, TAG, OLD, 1, CHANNEL, CLOCK);
            Config previous = Config.load(workspace);
            Path original = PublishedReleaseCache.manifestFile(workspace, previous, TAG, OLD);
            changeConfig(workspace, field, field.equals("endpoint") ? second.url() : "another-" + field);
            Config current = Config.load(workspace);
            FakeS3 remote = field.equals("endpoint") ? second : first;
            Manifest expected = manifest(TAG, OLD, "different-destination.txt");
            seedRemote(remote, current, CHANNEL, expected);
            int firstBefore = first.requests().size();
            int secondBefore = second.requests().size();
            Map<String, String> before = disk(workspace);

            try (Publish.Prepared prepared = Publish.prepare(workspace, TAG, CHANNEL, CLOCK)) {
                assertEquals(expected, prepared.baseline());
            }

            assertNotEquals(original, PublishedReleaseCache.manifestFile(workspace, current, TAG, OLD));
            assertEquals(baselineReads(current, CHANNEL, OLD), since(remote,
                    field.equals("endpoint") ? secondBefore : firstBefore));
            if (field.equals("endpoint")) {
                assertEquals(List.of(), since(first, firstBefore));
            } else {
                assertEquals(List.of(), since(second, secondBefore));
            }
            assertEquals(before, disk(workspace));
            assertFalse(Files.exists(PublishedReleaseCache.manifestFile(workspace, current, TAG, OLD)));
        }
    }

    @ParameterizedTest(name = "a {0} cached pair falls back without repairing the cache")
    @ValueSource(strings = {"missing", "manifest-only", "signature-only", "signed-malformed", "signed-invalid-json", "wrong-tag",
            "wrong-release", "tampered-manifest", "tampered-signature", "unreadable-manifest"})
    void anUnusableCachedPairFetchesBothRemoteObjectsWithoutPersistentWrites(String problem,
            @TempDir Path workspace) throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "payload.txt", "local");
            Config config = Config.load(workspace);
            Manifest expected = manifest(TAG, OLD, "remote.txt");
            seedRemote(s3, config, CHANNEL, expected);
            seedUnusablePair(workspace, config, problem);
            Map<String, String> before = disk(workspace);
            int objects = s3.objectCount();

            try (Publish.Prepared prepared = Publish.prepare(workspace, TAG, CHANNEL, CLOCK)) {
                assertEquals(expected, prepared.baseline());
            }

            assertEquals(baselineReads(config, CHANNEL, OLD), s3.requests());
            assertEquals(before, disk(workspace));
            assertEquals(objects, s3.objectCount());
        }
    }

    @Test
    void aFailedRemoteFallbackDoesNotTurnAnInvalidCacheIntoAnEmptyBaseline(@TempDir Path workspace)
            throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "payload.txt", "local");
            Config config = Config.load(workspace);
            seedRemote(s3, config, CHANNEL, manifest(TAG, OLD, "remote.txt"));
            seedUnusablePair(workspace, config, "signed-malformed");
            s3.fail("GET", key(config, ObjectKeys.signature(TAG, OLD)), 403);
            Map<String, String> before = disk(workspace);
            byte[] pointer = s3.object(key(config, ObjectKeys.channel(TAG, CHANNEL)));
            int objects = s3.objectCount();

            UserError failure = assertThrows(UserError.class,
                    () -> Publish.prepare(workspace, TAG, CHANNEL, CLOCK));

            assertTrue(failure.getMessage().contains("access denied"), failure.getMessage());
            assertEquals(baselineReads(config, CHANNEL, OLD), s3.requests());
            assertEquals(before, disk(workspace));
            assertEquals(objects, s3.objectCount());
            assertArrayEquals(pointer, s3.object(key(config, ObjectKeys.channel(TAG, CHANNEL))));
        }
    }

    @ParameterizedTest(name = "a {0} current public key does not trust an old cached pair")
    @ValueSource(strings = {"missing", "malformed", "rotated"})
    void theCurrentTrustedKeyRemainsRequiredEvenWhenTheCachedPairWasPreviouslyValid(String keyState,
            @TempDir Path workspace) throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "payload.txt", "local");
            logs.begin(workspace);
            Publish.run(workspace, TAG, OLD, 1, CHANNEL, CLOCK);
            Config config = Config.load(workspace);
            Path publicKey = config.publicKeyFile(workspace);
            String message;
            if (keyState.equals("missing")) {
                Files.delete(publicKey);
                message = "trusted public key";
            } else if (keyState.equals("malformed")) {
                Files.writeString(publicKey, "not a PEM key");
                message = "PEM";
            } else {
                // RFC 8032 section 7.1 test vector 2 supplies a different, deterministic public key.
                Files.writeString(publicKey, ReleaseSigning.pem("PUBLIC KEY", HexFormat.of().parseHex(
                        "302a300506032b65700321003d4017c3e843895a92b70aa74d1b7ebc"
                                + "9c982ccf2ec4968cc0cd55f12af4660c")));
                message = "signature";
            }
            int requestsBefore = s3.requests().size();
            int objects = s3.objectCount();
            Map<String, String> before = disk(workspace);

            UserError failure = assertThrows(UserError.class,
                    () -> Publish.prepare(workspace, TAG, CHANNEL, CLOCK));

            assertTrue(failure.getMessage().contains(message), failure.getMessage());
            assertEquals(keyState.equals("rotated") ? baselineReads(config, CHANNEL, OLD)
                    : channelReads(config, CHANNEL), since(s3, requestsBefore));
            assertEquals(before, disk(workspace));
            assertEquals(objects, s3.objectCount());
        }
    }

    @Test
    void theRemotePointerSelectsTheBaselineInsteadOfTheNewestCachedRelease(@TempDir Path workspace)
            throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "payload.txt", "local");
            logs.begin(workspace);
            Publish.run(workspace, TAG, "newest", 1, CHANNEL, CLOCK);
            Config config = Config.load(workspace);
            Manifest selected = manifest(TAG, "rollback", "selected.txt");
            seedRemote(s3, config, CHANNEL, selected);
            int requestsBefore = s3.requests().size();
            Map<String, String> before = disk(workspace);

            try (Publish.Prepared prepared = Publish.prepare(workspace, TAG, CHANNEL, CLOCK)) {
                assertEquals(selected, prepared.baseline());
            }

            assertEquals(baselineReads(config, CHANNEL, "rollback"), since(s3, requestsBefore));
            assertEquals(before, disk(workspace));
        }
    }

    @Test
    void aPreparedPublicationRemembersTheExactPublishedPairAfterAdvancingItsChannel(@TempDir Path workspace)
            throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "payload.txt", "prepared");
            try (Publish.Prepared prepared = Publish.prepare(workspace, TAG, CHANNEL, CLOCK)) {
                logs.begin(workspace);

                prepared.publish(NEXT, 1);

                Config config = Config.load(workspace);
                assertPublishedPair(workspace, config, s3, NEXT);
                assertEquals(NEXT, Pointer.parse(s3.text(key(config, ObjectKeys.channel(TAG, CHANNEL))),
                        "published channel").releaseId());
            }
        }
    }

    @Test
    void aPointerlessPublicationRemembersItsPairWithoutCreatingAnyChannel(@TempDir Path workspace)
            throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "payload.txt", "pointerless");
            logs.begin(workspace);

            Publish.run(workspace, TAG, NEXT, 1, (String) null, CLOCK);

            Config config = Config.load(workspace);
            assertPublishedPair(workspace, config, s3, NEXT);
            assertNull(s3.object(key(config, ObjectKeys.channel(TAG, ObjectKeys.DEFAULT_CHANNEL))));
            assertEquals(List.of("PUT " + key(config, ObjectKeys.release(TAG, NEXT)),
                    "PUT " + key(config, ObjectKeys.signature(TAG, NEXT))), s3.requests().stream()
                    .filter(request -> request.startsWith("PUT ") && request.contains("/metadata/")).toList());
        }
    }

    @Test
    void aFailedSignatureUploadDoesNotRememberTheUnpublishedRelease(@TempDir Path workspace) throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "payload.txt", "local");
            Config config = Config.load(workspace);
            s3.fail("PUT", key(config, ObjectKeys.signature(TAG, NEXT)), 403);
            logs.begin(workspace);

            UserError failure = assertThrows(UserError.class,
                    () -> Publish.run(workspace, TAG, NEXT, 1, CHANNEL, CLOCK));

            assertTrue(failure.getMessage().contains("access denied"), failure.getMessage());
            assertNoPair(workspace, config, NEXT);
            assertNull(s3.object(key(config, ObjectKeys.signature(TAG, NEXT))));
            assertNull(s3.object(key(config, ObjectKeys.channel(TAG, CHANNEL))));
            assertFalse(s3.requests().contains("PUT " + key(config, ObjectKeys.channel(TAG, CHANNEL))));
        }
    }

    @Test
    void aFailedPreparedChannelUploadDoesNotRememberTheUnpublishedRelease(@TempDir Path workspace) throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "payload.txt", "local");
            Config config = Config.load(workspace);
            byte[] originalPointer = TestFiles.bytes(new Pointer(OLD).toJson());
            seedRemote(s3, config, CHANNEL, manifest(TAG, OLD, "old.txt"));
            s3.fail("PUT", key(config, ObjectKeys.channel(TAG, CHANNEL)), 403);
            try (Publish.Prepared prepared = Publish.prepare(workspace, TAG, CHANNEL, CLOCK)) {
                logs.begin(workspace);

                UserError failure = assertThrows(UserError.class, () -> prepared.publish(NEXT, 1));

                assertTrue(failure.getMessage().contains("access denied"), failure.getMessage());
                assertNoPair(workspace, config, NEXT);
                assertArrayEquals(originalPointer, s3.object(key(config, ObjectKeys.channel(TAG, CHANNEL))));
                assertArrayEquals(TestSigning.sign(TestFiles.bytes(s3.text(key(config, ObjectKeys.release(TAG, NEXT))))),
                        s3.object(key(config, ObjectKeys.signature(TAG, NEXT))));
            }
        }
    }

    @Test
    void aBlockedCacheFileWarnsWithoutFailingTheCompletedPublication(@TempDir Path workspace) throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "payload.txt", "local");
            Config config = Config.load(workspace);
            Path manifest = PublishedReleaseCache.manifestFile(workspace, config, TAG, NEXT);
            TestFiles.write(manifest, "sentinel.txt", "keep");
            Map<String, String> blocked = disk(manifest);
            logs.begin(workspace);

            Publish.run(workspace, TAG, NEXT, 1, CHANNEL, CLOCK);

            assertEquals(NEXT, Pointer.parse(s3.text(key(config, ObjectKeys.channel(TAG, CHANNEL))), "channel").releaseId());
            assertEquals(blocked, disk(manifest));
            assertTrue(logs.errText().toLowerCase(java.util.Locale.ROOT).contains("cache"), logs.errText());
        }
    }

    @Test
    void aSymlinkedCacheFileNeverOverwritesAnExternalSentinelOrFailsThePublication(@TempDir Path root)
            throws Exception {
        Path workspace = root.resolve("workspace");
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "payload.txt", "local");
            Config config = Config.load(workspace);
            Path manifest = PublishedReleaseCache.manifestFile(workspace, config, TAG, NEXT);
            Path sentinel = root.resolve("external-sentinel.txt");
            Files.writeString(sentinel, "external content stays untouched");
            Files.createDirectories(manifest.getParent());
            try {
                Files.createSymbolicLink(manifest, sentinel);
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                assumeTrue(false, "symbolic links require filesystem support and Windows privileges: " + e);
            }
            logs.begin(workspace);

            Publish.run(workspace, TAG, NEXT, 1, CHANNEL, CLOCK);

            assertEquals(NEXT, Pointer.parse(s3.text(key(config, ObjectKeys.channel(TAG, CHANNEL))), "channel").releaseId());
            assertEquals("external content stays untouched", Files.readString(sentinel));
            assertTrue(Files.isSymbolicLink(manifest));
            assertEquals(sentinel, Files.readSymbolicLink(manifest));
            assertTrue(logs.errText().toLowerCase(java.util.Locale.ROOT).contains("cache"), logs.errText());
        }
    }

    private static Manifest manifest(String tag, String release, String path) {
        String hash = Sha256.ofBytes(TestFiles.bytes(path));
        return new Manifest(tag, release, CLOCK.instant(), Manifest.SHA256,
                Map.of(path, new FileEntry(hash, TestFiles.bytes(path).length, hash)));
    }

    private static void seedRemote(FakeS3 s3, Config config, String channel, Manifest manifest) {
        // Signed metadata left by another publisher is the authoritative remote input.
        byte[] bytes = TestFiles.bytes(manifest.toJson());
        s3.put(key(config, ObjectKeys.channel(TAG, channel)), TestFiles.bytes(new Pointer(manifest.releaseId()).toJson()));
        s3.put(key(config, ObjectKeys.release(TAG, manifest.releaseId())), bytes);
        s3.put(key(config, ObjectKeys.signature(TAG, manifest.releaseId())), TestSigning.sign(bytes));
    }

    private static void seedUnusablePair(Path workspace, Config config, String problem) throws IOException {
        Path manifest = PublishedReleaseCache.manifestFile(workspace, config, TAG, OLD);
        Path signature = PublishedReleaseCache.signatureFile(workspace, config, TAG, OLD);
        byte[] bytes = TestFiles.bytes(problem.equals("signed-invalid-json") ? "{"
                : problem.equals("signed-malformed") ? "{}"
                : manifest(problem.equals("wrong-tag") ? "server" : TAG,
                        problem.equals("wrong-release") ? "another" : OLD, "cached.txt").toJson());
        byte[] signed = TestSigning.sign(bytes);
        if (problem.equals("tampered-manifest")) {
            bytes = TestFiles.bytes(manifest(TAG, OLD, "tampered.txt").toJson());
        }
        if (problem.equals("tampered-signature")) {
            signed[0] ^= 1;
        }
        if (!problem.equals("missing") && !problem.equals("signature-only")) {
            if (problem.equals("unreadable-manifest")) {
                Files.createDirectories(manifest);
            } else {
                TestFiles.write(manifest.getParent(), manifest.getFileName().toString(), bytes);
            }
        }
        if (!problem.equals("missing") && !problem.equals("manifest-only")) {
            TestFiles.write(signature.getParent(), signature.getFileName().toString(), signed);
        }
    }

    private static void assertPublishedPair(Path workspace, Config config, FakeS3 s3, String id) throws IOException {
        byte[] bytes = TestFiles.bytes(s3.text(key(config, ObjectKeys.release(TAG, id))));
        assertArrayEquals(bytes, Files.readAllBytes(PublishedReleaseCache.manifestFile(workspace, config, TAG, id)));
        byte[] signature = Files.readAllBytes(PublishedReleaseCache.signatureFile(workspace, config, TAG, id));
        assertEquals(ReleaseSigning.SIGNATURE_BYTES, signature.length);
        assertArrayEquals(s3.object(key(config, ObjectKeys.signature(TAG, id))), signature);
    }

    private static void assertNoPair(Path workspace, Config config, String id) {
        assertFalse(Files.exists(PublishedReleaseCache.manifestFile(workspace, config, TAG, id)));
        assertFalse(Files.exists(PublishedReleaseCache.signatureFile(workspace, config, TAG, id)));
    }

    private static void denyReleaseReads(FakeS3 s3, Config config, String id) {
        for (String method : List.of("GET", "HEAD")) {
            s3.fail(method, key(config, ObjectKeys.release(TAG, id)), 403);
            s3.fail(method, key(config, ObjectKeys.signature(TAG, id)), 403);
        }
    }

    private static void changeConfig(Path workspace, String field, String value) throws IOException {
        Path file = Layout.configFile(workspace);
        Map<String, Object> fields = Json.asObject(Json.parse(Files.readString(file)), file.toString());
        fields.put(field, value);
        Files.writeString(file, Json.write(fields));
    }

    private static List<String> channelReads(Config config, String channel) {
        return List.of("HEAD " + key(config, ObjectKeys.channel(TAG, channel)),
                "GET " + key(config, ObjectKeys.channel(TAG, channel)));
    }

    private static List<String> baselineReads(Config config, String channel, String id) {
        return List.of("HEAD " + key(config, ObjectKeys.channel(TAG, channel)),
                "GET " + key(config, ObjectKeys.channel(TAG, channel)),
                "GET " + key(config, ObjectKeys.release(TAG, id)),
                "GET " + key(config, ObjectKeys.signature(TAG, id)));
    }

    private static List<String> since(FakeS3 s3, int count) {
        List<String> requests = s3.requests();
        return requests.subList(count, requests.size());
    }

    private static String key(Config config, String object) {
        return "/" + config.bucket() + "/" + config.prefix() + object;
    }

    private static Map<String, String> disk(Path root) throws IOException {
        Map<String, String> result = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                String relative = root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
                result.put(relative, Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        ? "directory" : Sha256.ofFile(path));
            }
        }
        return result;
    }
}

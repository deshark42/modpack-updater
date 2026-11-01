package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.TestSigning;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.json.Json;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import io.github.deshark42.modpackupdater.model.Pointer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ResourceLock(TestLogs.RESOURCE)
class PromoteTest {
    private static final String TAG = "client";
    private static final String RELEASE = "archive-1";
    private static final String NEWER = "newer";
    private static final String BETA = "beta";
    private static final String LATEST = ObjectKeys.DEFAULT_CHANNEL;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void anExistingBetaReleaseReplacesLatestWithoutRewritingAnyOtherObject(@TempDir Path workspace)
            throws Exception {
        try (FakeS3 s3 = new FakeS3(); FakeS3 cdn = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3.url(), cdn.url());
            logs.begin(workspace);
            publish(workspace, TAG, RELEASE, BETA, "tested beta");
            publish(workspace, TAG, NEWER, LATEST, "newer latest");
            publish(workspace, "server", "independent", LATEST, "other tag");
            Map<String, byte[]> expected = publishedObjects(s3);
            Map<String, String> before = disk(workspace);
            int requests = s3.requests().size();
            long uploaded = s3.uploadedBytes();

            Promote.run(workspace, TAG, RELEASE, LATEST, CLOCK);

            byte[] pointer = TestFiles.bytes(new Pointer(RELEASE).toJson());
            expected.put(key(ObjectKeys.channel(TAG, LATEST)), pointer);
            assertBucket(s3, expected);
            assertEquals(RELEASE, Pointer.parse(s3.text(key(ObjectKeys.channel(TAG, BETA))), "beta").releaseId());
            assertEquals(before, disk(workspace));
            assertEquals(promotionRequests(LATEST), s3.requests().subList(requests, s3.requests().size()));
            assertEquals(pointer.length, s3.uploadedBytes() - uploaded);
            assertEquals(List.of(), cdn.requests());
            assertTrue(logs.outText().contains("verified the release signature for tag " + TAG + " release " + RELEASE));
            assertTrue(logs.outText().contains("promoted tag " + TAG + " release " + RELEASE + " to channel " + LATEST));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"canary", "release_candidate", "stable.1"})
    void aPointerlessReleaseCanCreateACustomChannelWithoutLocalPayloadOrPrivateKey(String channel,
            @TempDir Path root) throws Exception {
        Path publisher = root.resolve("publisher");
        Path workspace = root.resolve("promoter");
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(publisher, s3);
            logs.begin(publisher);
            publish(publisher, TAG, RELEASE, null, "pointerless payload");
            TestWorkspace.configure(workspace, s3);
            Files.delete(workspace.resolve(Layout.DEFAULT_PRIVATE_KEY));
            String trustedKey = Layout.STATE_DIR + "/keys/promotion-trusted.pem";
            Files.move(workspace.resolve(Layout.DEFAULT_PUBLIC_KEY), workspace.resolve(trustedKey));
            Map<String, Object> config = Json.asObject(Json.parse(Files.readString(Layout.configFile(workspace))), "config");
            config.put("trusted_public_key", trustedKey);
            config.put("signing_private_key", "unused-payload-private-key.pem");
            TestFiles.write(workspace, Layout.STATE_DIR + "/" + Layout.CONFIG, Json.write(config));
            Map<String, byte[]> expected = publishedObjects(s3);
            Map<String, String> before = disk(workspace);
            int requests = s3.requests().size();

            Promote.run(workspace, TAG, RELEASE, channel, CLOCK);

            expected.put(key(ObjectKeys.channel(TAG, channel)), TestFiles.bytes(new Pointer(RELEASE).toJson()));
            assertBucket(s3, expected);
            assertEquals(before, disk(workspace));
            assertFalse(Files.exists(Layout.baseDir(workspace, TAG)));
            assertFalse(Files.exists(Layout.knownBlobsFile(workspace)));
            assertFalse(Files.exists(Layout.currentFile(workspace, TAG)));
            assertEquals(promotionRequests(channel), s3.requests().subList(requests, s3.requests().size()));
        }
    }

    @Test
    void anAlreadySelectedReleaseCanBePromotedAgain(@TempDir Path workspace) throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            logs.begin(workspace);
            publish(workspace, TAG, RELEASE, LATEST, "already selected");
            Map<String, byte[]> before = publishedObjects(s3);
            Map<String, String> local = disk(workspace);
            int requests = s3.requests().size();

            Promote.run(workspace, TAG, RELEASE, LATEST, CLOCK);

            assertBucket(s3, before);
            assertEquals(local, disk(workspace));
            assertEquals(promotionRequests(LATEST), s3.requests().subList(requests, s3.requests().size()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-manifest", "missing-signature", "missing-key", "bad-signature",
            "unverified-malformed", "signed-malformed", "signed-invalid-json", "wrong-tag", "wrong-release",
            "manifest-forbidden", "manifest-network", "signature-network"})
    void unusableSourceMetadataNeverWritesAPointerOrAnyOtherObject(String problem, @TempDir Path workspace)
            throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            logs.begin(workspace);
            // A different writer left these deliberately broken release states in the bucket.
            Manifest source = new Manifest(problem.equals("wrong-tag") ? "server" : TAG,
                    problem.equals("wrong-release") ? "another" : RELEASE, CLOCK.instant(), Manifest.SHA256, Map.of());
            String text = switch (problem) {
                case "unverified-malformed", "signed-malformed" -> "{}";
                case "signed-invalid-json" -> "{";
                default -> source.toJson();
            };
            byte[] bytes = TestFiles.bytes(text);
            Map<String, byte[]> before = new TreeMap<>();
            before.put(key(ObjectKeys.channel(TAG, LATEST)), TestFiles.bytes(new Pointer(NEWER).toJson()));
            before.put(key(ObjectKeys.channel(TAG, BETA)), TestFiles.bytes(new Pointer(RELEASE).toJson()));
            if (!problem.equals("missing-manifest")) {
                before.put(key(ObjectKeys.release(TAG, RELEASE)), bytes);
            }
            if (!problem.equals("missing-signature")) {
                before.put(key(ObjectKeys.signature(TAG, RELEASE)),
                        problem.equals("bad-signature") || problem.equals("unverified-malformed")
                                ? TestSigning.sign(TestFiles.bytes("different manifest bytes")) : TestSigning.sign(bytes));
            }
            before.forEach(s3::put);
            String message = switch (problem) {
                case "missing-manifest", "missing-signature" -> "not found";
                case "missing-key" -> {
                    Files.delete(workspace.resolve(Layout.DEFAULT_PUBLIC_KEY));
                    yield "trusted public key";
                }
                case "bad-signature", "unverified-malformed" -> "signature";
                case "signed-malformed" -> "version";
                case "signed-invalid-json" -> "malformed json";
                case "wrong-tag", "wrong-release" -> "identity";
                case "manifest-forbidden" -> {
                    s3.fail("GET", key(ObjectKeys.release(TAG, RELEASE)), 403);
                    yield "access denied";
                }
                case "manifest-network" -> {
                    s3.fail("GET", key(ObjectKeys.release(TAG, RELEASE)), 500);
                    yield "500";
                }
                case "signature-network" -> {
                    s3.fail("GET", key(ObjectKeys.signature(TAG, RELEASE)), 500);
                    yield "500";
                }
                default -> throw new AssertionError(problem);
            };
            Map<String, String> local = disk(workspace);
            UserError failure = assertThrows(UserError.class,
                    () -> Promote.run(workspace, TAG, RELEASE, LATEST, CLOCK));

            assertTrue(failure.getMessage().contains(message), failure.getMessage());
            assertBucket(s3, before);
            assertEquals(local, disk(workspace));
            List<String> reads = switch (problem) {
                case "missing-key" -> List.of();
                case "missing-manifest", "manifest-forbidden", "manifest-network" ->
                        List.of("GET " + key(ObjectKeys.release(TAG, RELEASE)));
                default -> promotionRequests(LATEST).subList(0, 2);
            };
            assertEquals(reads, s3.requests());
            assertEquals(0, s3.uploadedBytes());
        }
    }

    @Test
    void aFailedPointerUploadLeavesThePreviousPointerAndAllReleaseBytesUntouched(@TempDir Path workspace)
            throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            logs.begin(workspace);
            publish(workspace, TAG, RELEASE, BETA, "tested beta");
            publish(workspace, TAG, NEWER, LATEST, "current latest");
            Map<String, byte[]> before = publishedObjects(s3);
            Map<String, String> local = disk(workspace);
            int requests = s3.requests().size();
            long uploaded = s3.uploadedBytes();
            s3.failPut(key(ObjectKeys.channel(TAG, LATEST)));

            UserError failure = assertThrows(UserError.class,
                    () -> Promote.run(workspace, TAG, RELEASE, LATEST, CLOCK));

            assertTrue(failure.getMessage().contains("cannot write"), failure.getMessage());
            assertTrue(failure.getMessage().contains("500"), failure.getMessage());
            assertBucket(s3, before);
            assertEquals(local, disk(workspace));
            assertEquals(uploaded, s3.uploadedBytes());
            assertEquals(promotionRequests(LATEST), s3.requests().subList(requests, s3.requests().size()));
            assertFalse(logs.outText().contains("promoted tag"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"tag", "release id", "channel"})
    void everyRequiredNameIsValidatedBeforeStorageOrLocalMutation(String missing, @TempDir Path workspace)
            throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            logs.begin(workspace);
            Map<String, String> before = disk(workspace);

            UserError failure = assertThrows(UserError.class, () -> Promote.run(workspace,
                    missing.equals("tag") ? null : TAG, missing.equals("release id") ? null : RELEASE,
                    missing.equals("channel") ? null : LATEST, CLOCK));

            assertTrue(failure.getMessage().contains("the " + missing + " must not be empty"), failure.getMessage());
            assertEquals(before, disk(workspace));
            assertEquals(List.of(), s3.requests());
            assertEquals(0, s3.objectCount());
        }
    }

    private static void publish(Path workspace, String tag, String release, String channel, String content)
            throws IOException {
        TestWorkspace.base(workspace, tag, "payload.txt", content);
        Publish.run(workspace, tag, release, Publish.DEFAULT_CACHE_MAX_AGE_DAYS, channel, CLOCK);
    }

    private static List<String> promotionRequests(String channel) {
        return List.of("GET " + key(ObjectKeys.release(TAG, RELEASE)),
                "GET " + key(ObjectKeys.signature(TAG, RELEASE)), "PUT " + key(ObjectKeys.channel(TAG, channel)));
    }

    private static Map<String, byte[]> publishedObjects(FakeS3 s3) {
        Map<String, byte[]> objects = new TreeMap<>();
        for (FakeS3.Request request : s3.requestsDetailed()) {
            if (request.method().equals("PUT")) {
                objects.put(request.path(), s3.object(request.path()));
            }
        }
        assertEquals(s3.objectCount(), objects.size());
        return objects;
    }

    private static void assertBucket(FakeS3 s3, Map<String, byte[]> expected) {
        assertEquals(expected.size(), s3.objectCount());
        expected.forEach((path, bytes) -> assertArrayEquals(bytes, s3.object(path), path));
    }

    private static Map<String, String> disk(Path workspace) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (var paths = Files.walk(workspace)) {
            paths.forEach(file -> files.put(workspace.relativize(file).toString().replace('\\', '/'),
                    Files.isDirectory(file) ? "directory" : Sha256.ofFile(file)));
        }
        return files;
    }

    private static String key(String relative) {
        return TestWorkspace.bucketPath(relative);
    }
}

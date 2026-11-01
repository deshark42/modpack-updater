package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.TestArchives;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.TestSigning;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.model.ChunkTable;
import io.github.deshark42.modpackupdater.model.Chunking;
import io.github.deshark42.modpackupdater.model.FileEntry;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import io.github.deshark42.modpackupdater.model.Plan;
import io.github.deshark42.modpackupdater.model.Pointer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@ResourceLock(TestLogs.RESOURCE)
class PublishPreviewTest {
    private static final String TAG = "client";
    private static final String CHANNEL = "beta";
    private static final String OLD = "previous";
    private static final String NEXT = "next";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void aPreviewUsesTheVerifiedAuthoritativeBaselineAndTheActualMergedPayload(@TempDir Path workspace)
            throws Exception {
        try (FakeS3 s3 = new FakeS3(); FakeS3 cdn = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3.url(), cdn.url());
            Manifest baseline = manifest(TAG, OLD, Map.of("base.txt", entry("base"),
                    "pack/replaced.txt", entry("old"), "pack/deleted.txt", entry("deleted")));
            seedBaseline(s3, baseline);
            TestWorkspace.base(workspace, TAG, "base.txt", "base");
            TestWorkspace.base(workspace, TAG, "restore.txt", "removed base");
            TestArchives.write(Layout.basePackDir(workspace, TAG).resolve("pack/input.zip"), Map.of(
                    "replaced.txt", TestFiles.bytes("archive"), "added.txt", TestFiles.bytes("added"),
                    "deleted.txt", TestFiles.bytes("deleted")));
            TestWorkspace.overlay(workspace, TAG, "pack/replaced.txt", "overlay");
            TestWorkspace.overlay(workspace, TAG, "restore.txt", "restored");
            TestWorkspace.remove(workspace, TAG, "deleted.txt\nrestore.txt\n");
            TestFiles.write(Layout.tempDir(workspace), "unrelated.txt", "keep");
            Map<String, String> before = disk(workspace);
            Map<String, FileEntry> expected = new TreeMap<>(Map.of("base.txt", entry("base"),
                    "pack/added.txt", entry("added"), "pack/replaced.txt", entry("overlay"),
                    "restore.txt", entry("restored")));

            try (Publish.Prepared prepared = Publish.prepare(workspace, TAG, CHANNEL, CLOCK)) {
                assertEquals(baseline, prepared.baseline());
                assertEquals(expected, prepared.files());
                assertEquals(List.copyOf(expected.keySet()), List.copyOf(prepared.files().keySet()));
                Plan diff = Plan.between(prepared.baseline().files(), prepared.files());
                assertEquals(List.of("pack/added.txt", "restore.txt"), diff.added());
                assertEquals(List.of("pack/replaced.txt"), diff.replaced());
                assertEquals(List.of("pack/deleted.txt"), diff.deleted());
                assertThrows(UnsupportedOperationException.class, () -> prepared.files().clear());
                assertEquals(before.get(relative(workspace, Layout.configFile(workspace))),
                        Sha256.ofFile(Layout.configFile(workspace)));
                assertNoPublishWrites(workspace, s3);
                assertEquals(List.of("HEAD " + key(ObjectKeys.channel(TAG, CHANNEL)),
                        "GET " + key(ObjectKeys.channel(TAG, CHANNEL)), "GET " + key(ObjectKeys.release(TAG, OLD)),
                        "GET " + key(ObjectKeys.signature(TAG, OLD))), s3.requests());
                assertEquals(List.of(), cdn.requests());
                assertEquals(3, s3.objectCount());
            }
            assertEquals(before, disk(workspace));
            assertEquals(List.of("unrelated.txt"), children(Layout.tempDir(workspace)));
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = CHANNEL)
    void onlyAnAbsentPointerOrPointerlessSelectionHasNoBaseline(String channel, @TempDir Path workspace)
            throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "new.txt", "new");
            Files.delete(workspace.resolve(Layout.DEFAULT_PUBLIC_KEY));
            Map<String, String> before = disk(workspace);

            try (Publish.Prepared prepared = Publish.prepare(workspace, TAG, channel, CLOCK)) {
                assertNull(prepared.baseline());
                assertEquals(Map.of("new.txt", entry("new")), prepared.files());
                assertEquals(channel == null ? List.of() : List.of("HEAD " + key(ObjectKeys.channel(TAG, channel))),
                        s3.requests());
                assertNoPublishWrites(workspace, s3);
            }
            assertEquals(before, disk(workspace));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pointer", "forbidden", "missing-manifest", "missing-signature", "manifest-network",
            "malformed-manifest", "wrong-tag", "wrong-release", "signature", "trusted-key"})
    void anUnusableBaselineFailsClosedInsteadOfBecomingAnEmptyRelease(String problem, @TempDir Path workspace)
            throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "new.txt", "new");
            Manifest baseline = manifest(problem.equals("wrong-tag") ? "server" : TAG,
                    problem.equals("wrong-release") ? "another" : OLD, Map.of("old.txt", entry("old")));
            byte[] bytes = TestFiles.bytes(problem.equals("malformed-manifest") ? "{}" : baseline.toJson());
            byte[] pointer = TestFiles.bytes(problem.equals("pointer") ? "{}" : new Pointer(OLD).toJson());
            s3.put(key(ObjectKeys.channel(TAG, CHANNEL)), pointer);
            if (!problem.equals("missing-manifest")) {
                s3.put(key(ObjectKeys.release(TAG, OLD)), bytes);
            }
            if (!problem.equals("missing-signature")) {
                s3.put(key(ObjectKeys.signature(TAG, OLD)), problem.equals("signature")
                        ? new byte[0] : TestSigning.sign(bytes));
            }
            String message = switch (problem) {
                case "pointer" -> "version";
                case "forbidden" -> {
                    s3.fail("HEAD", key(ObjectKeys.channel(TAG, CHANNEL)), 403);
                    yield "access denied";
                }
                case "missing-manifest", "missing-signature" -> "not found";
                case "manifest-network" -> {
                    s3.fail("GET", key(ObjectKeys.release(TAG, OLD)), 500);
                    yield "500";
                }
                case "malformed-manifest" -> "version";
                case "wrong-tag", "wrong-release" -> "identity";
                case "signature" -> "signature";
                case "trusted-key" -> {
                    Files.delete(workspace.resolve(Layout.DEFAULT_PUBLIC_KEY));
                    yield "trusted public key";
                }
                default -> throw new AssertionError(problem);
            };
            Map<String, String> before = disk(workspace);
            int objects = s3.objectCount();

            UserError failure = assertThrows(UserError.class,
                    () -> Publish.prepare(workspace, TAG, CHANNEL, CLOCK));

            assertTrue(failure.getMessage().contains(message), failure.getMessage());
            assertEquals(before, disk(workspace));
            assertEquals(objects, s3.objectCount());
            assertArrayEquals(pointer, s3.object(key(ObjectKeys.channel(TAG, CHANNEL))));
            assertNoPublishWrites(workspace, s3);
        }
    }

    @Test
    void publishingUsesThePreparedArchiveAndTheSameManifestEntries(@TempDir Path workspace) throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            Path archive = TestArchives.write(Layout.basePackDir(workspace, TAG).resolve("input.zip"),
                    Map.of("packed.txt", TestFiles.bytes("prepared")));
            Map<String, FileEntry> shown;
            try (Publish.Prepared prepared = Publish.prepare(workspace, TAG, CHANNEL, CLOCK)) {
                shown = prepared.files();
                // Removing the original archive proves commit does not extract or scan it a second time.
                Files.delete(archive);
                logs.begin(workspace, "publish-preview", CLOCK);

                prepared.publish(NEXT, Publish.DEFAULT_CACHE_MAX_AGE_DAYS);

                Manifest published = Manifest.parse(s3.text(key(ObjectKeys.release(TAG, NEXT))), "published");
                assertEquals(shown, published.files());
                assertEquals(NEXT, published.releaseId());
                assertEquals(CLOCK.instant(), published.publishedAt());
                assertEquals(NEXT, Pointer.parse(s3.text(key(ObjectKeys.channel(TAG, CHANNEL))), "channel").releaseId());
                assertArrayEquals(TestFiles.bytes("prepared"), s3.object(key(ObjectKeys.blob(entry("prepared").hash()))));
                assertArrayEquals(TestSigning.sign(TestFiles.bytes(published.toJson())),
                        s3.object(key(ObjectKeys.signature(TAG, NEXT))));
                assertTrue(Files.isRegularFile(Layout.knownBlobsFile(workspace)));
                assertEquals(List.of("PUT " + key(ObjectKeys.release(TAG, NEXT)),
                        "PUT " + key(ObjectKeys.signature(TAG, NEXT)), "PUT " + key(ObjectKeys.channel(TAG, CHANNEL))),
                        s3.requests().stream().filter(request -> request.startsWith("PUT ")
                                && request.contains("/metadata/")).toList());
            }
            assertEquals(List.of(), children(Layout.tempDir(workspace)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"content", "duplicate", "empty"})
    void changedPreparedFilesFailBeforeAnyPublishMutationEvenWhenTheirChunksAlreadyExist(String change,
            @TempDir Path workspace) throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "first.txt", "same");
            TestWorkspace.base(workspace, TAG, "second.txt", "same");
            TestWorkspace.base(workspace, TAG, "empty.txt", "");
            s3.put(key(ObjectKeys.blob(entry("same").hash())), TestFiles.bytes("same"));
            String changed = change.equals("duplicate") ? "second.txt" : change.equals("empty") ? "empty.txt" : "first.txt";
            try (Publish.Prepared prepared = Publish.prepare(workspace, TAG, CHANNEL, CLOCK)) {
                Path file = Layout.baseDir(workspace, TAG).resolve(changed);
                FileTime modified = Files.getLastModifiedTime(file);
                Files.writeString(file, change.equals("empty") ? "x" : "edit");
                Files.setLastModifiedTime(file, modified);
                Map<String, String> before = disk(workspace);
                int objects = s3.objectCount();

                UserError failure = assertThrows(UserError.class,
                        () -> prepared.publish(NEXT, Publish.DEFAULT_CACHE_MAX_AGE_DAYS));

                assertTrue(failure.getMessage().contains(changed + " changed"), failure.getMessage());
                assertEquals(before, disk(workspace));
                assertEquals(objects, s3.objectCount());
                assertNull(s3.object(key(ObjectKeys.release(TAG, NEXT))));
                assertNull(s3.object(key(ObjectKeys.channel(TAG, CHANNEL))));
                assertNoPublishWrites(workspace, s3);
            }
        }
    }

    @Test
    void anAdvancedChannelInvalidatesThePreviewBeforeAnyPublishWrites(@TempDir Path workspace) throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "new.txt", "new");
            seedBaseline(s3, manifest(TAG, OLD, Map.of("old.txt", entry("old"))));
            try (Publish.Prepared prepared = Publish.prepare(workspace, TAG, CHANNEL, CLOCK)) {
                byte[] advanced = TestFiles.bytes(new Pointer("advanced").toJson());
                s3.put(key(ObjectKeys.channel(TAG, CHANNEL)), advanced);
                Map<String, String> before = disk(workspace);
                int objects = s3.objectCount();

                UserError failure = assertThrows(UserError.class,
                        () -> prepared.publish(NEXT, Publish.DEFAULT_CACHE_MAX_AGE_DAYS));

                assertTrue(failure.getMessage().contains("channel " + CHANNEL + " changed"), failure.getMessage());
                assertEquals(before, disk(workspace));
                assertEquals(objects, s3.objectCount());
                assertArrayEquals(advanced, s3.object(key(ObjectKeys.channel(TAG, CHANNEL))));
                assertNull(s3.object(key(ObjectKeys.release(TAG, NEXT))));
                assertNoPublishWrites(workspace, s3);
            }
        }
    }

    @Test
    void aChangedDestinationInvalidatesThePreviewWithoutFillingConfigOrWritingObjects(@TempDir Path workspace)
            throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "new.txt", "new");
            try (Publish.Prepared prepared = Publish.prepare(workspace, TAG, CHANNEL, CLOCK)) {
                String original = Files.readString(Layout.configFile(workspace));
                Files.writeString(Layout.configFile(workspace), original.replace(TestWorkspace.BUCKET, "another-bucket"));
                Map<String, String> before = disk(workspace);

                UserError failure = assertThrows(UserError.class,
                        () -> prepared.publish(NEXT, Publish.DEFAULT_CACHE_MAX_AGE_DAYS));

                assertTrue(failure.getMessage().contains("configuration changed"), failure.getMessage());
                assertEquals(before, disk(workspace));
                assertEquals(0, s3.objectCount());
                assertNoPublishWrites(workspace, s3);
            }
        }
    }

    @Test
    void failedPreparationRemovesOnlyItsOwnedArchiveStage(@TempDir Path workspace) throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            TestArchives.write(Layout.basePackDir(workspace, TAG).resolve("input.zip"),
                    Map.of("packed.txt", TestFiles.bytes("prepared")));
            TestWorkspace.overlay(workspace, TAG, Layout.STATE_DIR + "/payload.txt", "reserved");
            TestFiles.write(Layout.tempDir(workspace), "unrelated.txt", "keep");
            Map<String, String> before = disk(workspace);

            UserError failure = assertThrows(UserError.class,
                    () -> Publish.prepare(workspace, TAG, CHANNEL, CLOCK));

            assertTrue(failure.getMessage().contains("reserved state directory"), failure.getMessage());
            assertEquals(before, disk(workspace));
            assertEquals(List.of("unrelated.txt"), children(Layout.tempDir(workspace)));
            assertNoPublishWrites(workspace, s3);
            assertEquals(0, s3.objectCount());
        }
    }

    @Test
    void aScriptPublishDoesNotReadTheChannelOrRequireATrustedPublicKey(@TempDir Path workspace) throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "new.txt", "new");
            Files.delete(workspace.resolve(Layout.DEFAULT_PUBLIC_KEY));
            s3.put(key(ObjectKeys.channel(TAG, CHANNEL)), TestFiles.bytes("broken pointer"));
            logs.begin(workspace);

            Publish.run(workspace, TAG, NEXT, Publish.DEFAULT_CACHE_MAX_AGE_DAYS, CHANNEL, CLOCK);

            assertEquals(Map.of("new.txt", entry("new")),
                    Manifest.parse(s3.text(key(ObjectKeys.release(TAG, NEXT))), "published").files());
            assertEquals(NEXT, Pointer.parse(s3.text(key(ObjectKeys.channel(TAG, CHANNEL))), "channel").releaseId());
            assertFalse(s3.requests().stream().anyMatch(request -> request.startsWith("GET ")));
        }
    }

    private static Manifest manifest(String tag, String release, Map<String, FileEntry> files) {
        return new Manifest(tag, release, CLOCK.instant(), Manifest.SHA256, files);
    }

    private static FileEntry entry(String content) {
        byte[] bytes = TestFiles.bytes(content);
        String hash = Sha256.ofBytes(bytes);
        return new FileEntry(hash, bytes.length, new ChunkTable(Chunking.DEFAULT, bytes.length,
                bytes.length == 0 ? List.of() : List.of(hash)).indexId());
    }

    private static void seedBaseline(FakeS3 s3, Manifest baseline) {
        // Another publisher's signed metadata is the remote input being previewed.
        byte[] bytes = TestFiles.bytes(baseline.toJson());
        s3.put(key(ObjectKeys.channel(TAG, CHANNEL)), TestFiles.bytes(new Pointer(baseline.releaseId()).toJson()));
        s3.put(key(ObjectKeys.release(TAG, baseline.releaseId())), bytes);
        s3.put(key(ObjectKeys.signature(TAG, baseline.releaseId())), TestSigning.sign(bytes));
    }

    private static void assertNoPublishWrites(Path workspace, FakeS3 s3) {
        assertFalse(s3.requests().stream().anyMatch(request -> request.startsWith("PUT ")), s3.requests().toString());
        assertFalse(Files.exists(Layout.knownBlobsFile(workspace)));
        assertFalse(Files.exists(Layout.logsDir(workspace)));
    }

    private static Map<String, String> disk(Path workspace) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (var paths = Files.walk(workspace)) {
            paths.filter(Files::isRegularFile).forEach(file -> files.put(relative(workspace, file), Sha256.ofFile(file)));
        }
        return files;
    }

    private static String relative(Path workspace, Path file) {
        return workspace.relativize(file).toString().replace('\\', '/');
    }

    private static List<String> children(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    private static String key(String relative) {
        return TestWorkspace.bucketPath(relative);
    }
}

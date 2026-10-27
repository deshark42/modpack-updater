package io.github.deshark42.modpackupdater.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.command.Publish;
import io.github.deshark42.modpackupdater.command.Update;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import io.github.deshark42.modpackupdater.model.Pointer;
import io.github.deshark42.modpackupdater.model.RemoveRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@ResourceLock(TestLogs.RESOURCE)
class CliTest {

    private static final String TAG = "client";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2024-01-02T03:04:05Z"), ZoneOffset.UTC);

    private record Captured(String outText, String errText) {
    }

    private Captured logs = new Captured("", "");

    private int run(Path cwd, String... args) {
        try (TestLogs capture = new TestLogs()) {
            int exitCode = Cli.run(args, cwd, capture.out(), capture.err(), CLOCK);
            logs = new Captured(capture.outText(), capture.errText());
            return exitCode;
        }
    }

    private void assertUsage(Path cwd, String fragment, String... args) throws IOException {
        assertEquals(Cli.USAGE_ERROR, run(cwd, args), logs.errText());
        assertTrue(logs.errText().contains(fragment), logs.errText());
        assertEquals("", logs.outText());
        try (var entries = Files.list(cwd)) {
            assertEquals(List.of(), entries.toList(), "usage errors create no workspace files");
        }
    }

    private void publish(Path workspace, String tag, String releaseId, String path, String content)
            throws IOException {
        TestWorkspace.base(workspace, tag, path, content);
        Publish.run(workspace, tag, releaseId, Publish.DEFAULT_CACHE_MAX_AGE_DAYS, CLOCK);
    }

    private static String get(String objectKey) {
        return "GET " + TestWorkspace.bucketPath(objectKey);
    }

    private static List<String> requestsSince(FakeS3 s3, int offset) {
        List<String> requests = s3.requests();
        return requests.subList(offset, requests.size());
    }

    private static List<String> latestRequests(FakeS3 s3, int offset) {
        return requestsSince(s3, offset).stream()
                .filter(request -> request.equals(get(ObjectKeys.latest("common")))
                        || request.equals(get(ObjectKeys.latest(TAG)))
                        || request.equals(get(ObjectKeys.latest("server"))))
                .toList();
    }

    private static Stream<Arguments> invalidBatches() {
        return Stream.of(
                Arguments.of("invalid later tag", Cli.FAILURE, "invalid tag",
                        List.of("update", "--tag", "common", "--tag", "../client")),
                Arguments.of("multiple tags with a pinned release", Cli.USAGE_ERROR, "--id",
                        List.of("update", "--tag", "common", "--tag", TAG, "--id", "1")),
                Arguments.of("later tag without a value", Cli.USAGE_ERROR, "needs a value",
                        List.of("update", "--tag", "common", "--tag")));
    }

    private static Stream<Arguments> optionUsageErrors() {
        return Stream.of(
                Arguments.of("update without --tag", "needs --tag", List.of("update")),
                Arguments.of("publish without --id", "needs --id", List.of("publish", "--tag", TAG)),
                Arguments.of("init with --id", "init does not take --id",
                        List.of("init", "--tag", TAG, "--id", "1.0")),
                Arguments.of("keygen with --tag", "keygen only takes --workspace", List.of("keygen", "--tag", TAG)),
                Arguments.of("keygen with --id", "keygen only takes --workspace", List.of("keygen", "--id", "1")),
                Arguments.of("keygen with cache age", "keygen only takes --workspace",
                        List.of("keygen", "--cache-max-age-days", "1")),
                Arguments.of("option without a value", "needs a value", List.of("update", "--tag")));
    }

    private static Stream<Arguments> badCacheMaxAge() {
        return Stream.of(
                Arguments.of("publish with zero days", "cache-max-age-days needs a positive number of days",
                        List.of("publish", "--tag", TAG, "--id", "2.9.0",
                                "--cache-max-age-days", "0")),
                Arguments.of("publish with a word", "cache-max-age-days needs a positive number of days",
                        List.of("publish", "--tag", TAG, "--id", "2.9.0",
                                "--cache-max-age-days", "soon")),
                Arguments.of("update with the option", "update does not take --cache-max-age-days",
                        List.of("update", "--tag", TAG, "--cache-max-age-days", "7")),
                Arguments.of("init with the option", "init does not take --cache-max-age-days",
                        List.of("init", "--cache-max-age-days", "7")));
    }

    @Test
    void keygenWritesKeysToTheWorkspaceSelectedOnTheCommandLine(@TempDir Path root) {
        Path workspace = root.resolve("publisher");
        assertEquals(Cli.OK, run(root, "keygen", "--workspace", workspace.toString()), logs.errText());
        assertTrue(Files.isRegularFile(workspace.resolve(Layout.DEFAULT_PRIVATE_KEY)));
        assertTrue(Files.isRegularFile(workspace.resolve(Layout.DEFAULT_PUBLIC_KEY)));
        assertTrue(logs.outText().contains("public key SHA-256 fingerprint"), logs.outText());
        assertFalse(Files.exists(root.resolve(Layout.STATE_DIR)), "the current directory is not the selected workspace");
        assertEquals("", logs.errText());
    }

    @Test
    void noCommandIsAUsageError(@TempDir Path cwd) throws IOException {
        assertUsage(cwd, "no command");
    }

    @ParameterizedTest(name = "unknown option {0} is rejected")
    @ValueSource(strings = {"--wat", "--quiet", "--verbose", "--dry-run"})
    void anUnknownOptionIsAUsageError(String option, @TempDir Path cwd) throws IOException {
        assertUsage(cwd, "unknown option: " + option, "update", "--tag", TAG, option);
    }

    @Test
    void anUnknownCommandIsAUsageError(@TempDir Path cwd) throws IOException {
        assertUsage(cwd, "unknown command: sync", "sync", "--tag", TAG);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("optionUsageErrors")
    void eachCommandRejectsOptionsItDoesNotTake(String description, String fragment, List<String> args,
            @TempDir Path cwd) throws IOException {
        assertUsage(cwd, fragment, args.toArray(String[]::new));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badCacheMaxAge")
    void anUnusableCacheMaxAgeIsAUsageError(String description, String fragment, List<String> args,
            @TempDir Path cwd) throws IOException {
        assertUsage(cwd, fragment, args.toArray(String[]::new));
    }

    @Test
    void aBatchUpdatesEachDistinctTagInFirstOccurrenceOrder(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, "common", "common-2", "common.txt", "common");
            publish(publisher, TAG, "client-1", "client.txt", "client");
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            int before = s3.requests().size();

            assertEquals(Cli.OK, run(player, "update", "--tag", "common", "--tag", TAG, "--tag", "common"),
                    logs.errText());

            assertEquals("common-2", Pointer.read(Layout.currentFile(player, "common")).releaseId());
            assertEquals("client-1", Pointer.read(Layout.currentFile(player, TAG)).releaseId());
            assertEquals("common", Files.readString(player.resolve("common.txt")));
            assertEquals("client", Files.readString(player.resolve("client.txt")));
            assertEquals(List.of(get(ObjectKeys.latest("common")), get(ObjectKeys.latest(TAG))),
                    latestRequests(s3, before), "each distinct tag reads its own latest pointer once, in input order");
            assertEquals("", logs.errText());
        }
    }

    @Test
    void aFailureInALaterTagKeepsEarlierUpdatesAndStopsBeforeTheNextTag(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, "common", "1", "common.txt", "common");
            publish(publisher, TAG, "1", "client.txt", "old client");
            publish(publisher, "server", "1", "server.txt", "server");
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Update.run(player, TAG, null, CLOCK);
            publish(publisher, TAG, "2", "client.txt", "new client");
            s3.fail("GET", TestWorkspace.bucketPath(ObjectKeys.signature(TAG, "2")), 403);
            int before = s3.requests().size();

            assertEquals(Cli.FAILURE, run(player, "update", "--tag", "common", "--tag", TAG, "--tag", "server"),
                    logs.errText());

            assertTrue(logs.errText().contains("updating tag client failed"), logs.errText());
            assertTrue(logs.errText().contains("access denied"), logs.errText());
            assertEquals("1", Pointer.read(Layout.currentFile(player, "common")).releaseId());
            assertEquals("common", Files.readString(player.resolve("common.txt")));
            assertEquals("1", Pointer.read(Layout.currentFile(player, TAG)).releaseId());
            assertEquals("old client", Files.readString(player.resolve("client.txt")));
            assertFalse(Files.exists(Layout.releaseFile(player, TAG, "2")));
            assertFalse(Files.exists(Layout.backupsDir(player, TAG)));
            assertFalse(Files.exists(Layout.blobTempDir(player)));
            assertFalse(Files.exists(Layout.fileTempDir(player)));
            assertFalse(Files.exists(Layout.currentFile(player, "server")));
            assertFalse(Files.exists(player.resolve("server.txt")));
            assertEquals(List.of(get(ObjectKeys.latest("common")), get(ObjectKeys.latest(TAG))),
                    latestRequests(s3, before));
            List<String> requests = requestsSince(s3, before);
            int failedTagStart = requests.indexOf(get(ObjectKeys.latest(TAG)));
            assertTrue(failedTagStart >= 0, requests.toString());
            assertEquals(List.of(get(ObjectKeys.latest(TAG)), get(ObjectKeys.release(TAG, "2")),
                    get(ObjectKeys.signature(TAG, "2"))), requests.subList(failedTagStart, requests.size()),
                    "the failed tag downloads no content and the next tag is never contacted");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBatches")
    void anInvalidBatchFailsBeforeUpdatingAnyTag(String description, int exitCode, String fragment,
            List<String> args, @TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            String configBefore = Files.readString(Layout.configFile(player));

            assertEquals(exitCode, run(player, args.toArray(String[]::new)), logs.errText());

            assertTrue(logs.errText().contains(fragment), logs.errText());
            assertEquals(List.of(), s3.requests(), "validation precedes every tag update");
            assertFalse(Files.exists(Layout.currentFile(player, "common")));
            assertFalse(Files.exists(Layout.currentFile(player, TAG)));
            assertFalse(Files.exists(player.resolve("common.txt")));
            assertEquals(configBefore, Files.readString(Layout.configFile(player)));
        }
    }

    @Test
    void repeatingTheSameTagStillAllowsAPinnedRelease(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "1", "client.txt", "old client");
            publish(publisher, TAG, "2", "client.txt", "new client");
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            int before = s3.requests().size();

            assertEquals(Cli.OK, run(root, "update", "--tag", TAG, "--tag", TAG, "--id", "1",
                    "--workspace", player.toString()), logs.errText());

            assertEquals("1", Pointer.read(Layout.currentFile(player, TAG)).releaseId());
            assertEquals("old client", Files.readString(player.resolve("client.txt")));
            assertEquals(List.of(), latestRequests(s3, before), "a pinned update never reads latest");
        }
    }

    @Test
    void initUsesTheLastTagAndWorkspaceOptions(@TempDir Path root) {
        Path ignored = root.resolve("ignored");
        Path selected = root.resolve("selected");

        assertEquals(Cli.OK, run(root, "init", "--tag", "common", "--tag", TAG,
                "--workspace", ignored.toString(), "--workspace", selected.toString()), logs.errText());

        assertTrue(Files.isRegularFile(Layout.configFile(selected)));
        assertTrue(Files.isDirectory(Layout.baseDir(selected, TAG)));
        assertTrue(Files.isDirectory(Layout.basePackDir(selected, TAG)));
        assertTrue(Files.isDirectory(Layout.overlayDir(selected, TAG)));
        assertTrue(Files.isRegularFile(Layout.removeFile(selected, TAG)));
        assertEquals(0, RemoveRules.read(Layout.removeFile(selected, TAG)).size());
        assertFalse(Files.exists(Layout.baseDir(selected, "common")));
        assertFalse(Files.exists(ignored));
    }

    @Test
    void initWithoutATagCreatesOnlyWorkspaceState(@TempDir Path workspace) throws IOException {
        assertEquals(Cli.OK, run(workspace, "init"), logs.errText());

        assertTrue(Files.isRegularFile(Layout.configFile(workspace)));
        try (var entries = Files.list(workspace)) {
            assertEquals(List.of(Layout.stateDir(workspace)), entries.toList());
        }
        assertEquals("", logs.errText());
    }

    @Test
    void publishUsesTheLastValueOfRepeatedScalarOptions(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            TestWorkspace.base(publisher, TAG, "client.txt", "client");
            Path ignored = root.resolve("ignored");

            assertEquals(Cli.OK, run(root, "publish", "--tag", "common", "--tag", TAG,
                    "--id", "old", "--id", "1", "--workspace", ignored.toString(),
                    "--workspace", publisher.toString(), "--cache-max-age-days", "0",
                    "--cache-max-age-days", "7"), logs.errText());

            assertEquals("1", Pointer.parse(s3.text(TestWorkspace.bucketPath(ObjectKeys.latest(TAG))),
                    "latest").releaseId());
            assertNull(s3.object(TestWorkspace.bucketPath(ObjectKeys.latest("common"))));
            assertNull(s3.object(TestWorkspace.bucketPath(ObjectKeys.release(TAG, "old"))));
            assertFalse(Files.exists(ignored));
        }
    }

    @Test
    void aFailedCommandReportsAnErrorAndExitsWithFailure(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            TestFiles.write(Layout.baseDir(publisher, TAG), "mods/a.jar", TestFiles.bytes("A"));
            Publish.run(publisher, TAG, "2.9.0", Publish.DEFAULT_CACHE_MAX_AGE_DAYS, CLOCK);
            String latest = TestWorkspace.bucketPath(ObjectKeys.latest(TAG));
            String before = s3.text(latest);
            Files.delete(publisher.resolve(Layout.DEFAULT_PRIVATE_KEY));
            int requestsBefore = s3.requests().size();
            int objectsBefore = s3.objectCount();
            String cacheBefore = Files.readString(Layout.knownBlobsFile(publisher));

            assertEquals(Cli.FAILURE, run(publisher, "publish", "--tag", TAG, "--id", "2.9.1"),
                    logs.errText());

            assertTrue(logs.errText().contains(
                    "publishing release 2.9.1 of tag " + TAG + " failed"), logs.errText());
            assertTrue(logs.errText().contains("cannot read signing key"), logs.errText());
            assertEquals(before, s3.text(latest), "the pointer the failed run did not reach is unchanged");
            assertEquals("2.9.0", Pointer.parse(before, "latest").releaseId());
            assertEquals(List.of(), requestsSince(s3, requestsBefore), "key validation precedes storage access");
            assertEquals(objectsBefore, s3.objectCount());
            assertEquals(cacheBefore, Files.readString(Layout.knownBlobsFile(publisher)));
            assertNull(s3.object(TestWorkspace.bucketPath(ObjectKeys.release(TAG, "2.9.1"))));
            assertFalse(Files.exists(Layout.blobTempDir(publisher)));
            assertFalse(Files.exists(Layout.fileTempDir(publisher)));
        }
    }

    @Test
    void updateOutsideAWorkspaceIsAFailureNotAUsageError(@TempDir Path cwd) throws IOException {
        assertEquals(Cli.FAILURE, run(cwd, "update", "--tag", TAG));

        assertTrue(logs.errText().contains("updating tag " + TAG + " failed"), logs.errText());
        assertTrue(logs.errText().contains("config"), logs.errText());
        assertFalse(Files.exists(Layout.currentFile(cwd, TAG)));
        assertFalse(Files.exists(cwd.resolve(TAG)));
        assertFalse(Files.exists(Layout.configFile(cwd)));
    }
}

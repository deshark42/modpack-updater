package io.github.deshark42.modpackupdater.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import io.github.deshark42.modpackupdater.model.Pointer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CliTest {

    private static final String TAG = "client";

    private TestLogs logs = new TestLogs();

    private int run(Path cwd, String... args) {
        logs = new TestLogs();
        return Cli.run(args, cwd, logs.out(), logs.err());
    }

    private void assertUsage(Path cwd, String fragment, String... args) {
        assertEquals(Cli.USAGE_ERROR, run(cwd, args), logs.errText());
        assertTrue(logs.errText().contains(fragment), logs.errText());
    }

    private void publish(Path workspace, String tag, String releaseId, String path, String content)
            throws IOException {
        TestWorkspace.base(workspace, tag, path, content);
        assertEquals(Cli.OK, run(workspace, "publish", "--tag", tag, "--id", releaseId), logs.errText());
    }

    private static String get(String objectKey) {
        return "GET " + TestWorkspace.bucketPath(objectKey);
    }

    private static List<String> requestsSince(FakeS3 s3, int offset) {
        List<String> requests = s3.requests();
        return requests.subList(offset, requests.size());
    }

    private static List<String> latestRequests(FakeS3 s3, int offset) {
        return requestsSince(s3, offset).stream().filter(request -> request.endsWith("/latest.json")).toList();
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

    /** Command lines that the parser has to refuse, and the hint it has to print. */
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

    /** Values or commands that a --cache-max-age-days option cannot appear with. */
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
    void generatesKeysInTheWorkspaceSelectedOnTheCommandLine(@TempDir Path root) {
        Path workspace = root.resolve("publisher");
        assertEquals(Cli.OK, run(root, "keygen", "--workspace", workspace.toString()), logs.errText());
        assertTrue(Files.isRegularFile(workspace.resolve(Layout.DEFAULT_PRIVATE_KEY)));
        assertTrue(Files.isRegularFile(workspace.resolve(Layout.DEFAULT_PUBLIC_KEY)));
        assertTrue(logs.outText().contains("public key SHA-256 fingerprint"), logs.outText());
    }

    @Test
    void noCommandIsAUsageError(@TempDir Path cwd) {
        assertEquals(Cli.USAGE_ERROR, run(cwd));
        assertTrue(logs.errText().contains("usage: modpack-updater"), logs.errText());
    }

    @Test
    void unknownOptionIsAUsageError(@TempDir Path cwd) {
        assertUsage(cwd, "unknown option: --wat", "update", "--tag", TAG, "--wat");
    }

    @Test
    void quietAndVerboseAreNoLongerOptions(@TempDir Path cwd) {
        assertUsage(cwd, "unknown option: --quiet", "update", "--tag", TAG, "--quiet");
        assertUsage(cwd, "unknown option: --verbose", "update", "--tag", TAG, "--verbose");
    }

    @Test
    void unknownCommandIsAUsageError(@TempDir Path cwd) {
        assertUsage(cwd, "unknown command: sync", "sync", "--tag", TAG);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("optionUsageErrors")
    void eachCommandRejectsOptionsItDoesNotTake(String description, String fragment, List<String> args,
            @TempDir Path cwd) {
        assertUsage(cwd, fragment, args.toArray(String[]::new));
    }

    @Test
    void cacheMaxAgeIsAPublishOption(@TempDir Path cwd) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(cwd, s3);
            TestFiles.write(Layout.baseDir(cwd, TAG), "mods/a.jar", TestFiles.bytes("A"));

            assertEquals(Cli.OK, run(cwd, "publish", "--tag", TAG, "--id", "2.9.0",
                    "--cache-max-age-days", "7"), logs.errText());
            assertTrue(Files.exists(Layout.knownBlobsFile(cwd)), logs.errText());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badCacheMaxAge")
    void rejectsACacheMaxAgeItCannotUse(String description, String fragment, List<String> args,
            @TempDir Path cwd) {
        assertUsage(cwd, fragment, args.toArray(String[]::new));
    }

    @Test
    void publishesAndUpdatesThroughTheCli(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            TestFiles.write(Layout.baseDir(publisher, TAG), "mods/a.jar", TestFiles.bytes("A"));

            assertEquals(Cli.OK, run(publisher, "publish", "--tag", TAG, "--id", "2.9.0"), logs.errText());

            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            assertEquals(Cli.OK, run(root, "update", "--tag", TAG, "--workspace", player.toString()),
                    logs.errText());

            assertEquals("A", Files.readString(player.resolve("mods/a.jar")));
            assertTrue(logs.outText().contains("to release 2.9.0"), logs.outText());
        }
    }

    @Test
    void updatesEachDistinctTagInFirstOccurrenceOrder(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, "common", "common-1", "common.txt", "old common");
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
            String output = logs.outText();
            int completedCommon = output.indexOf("updated tag common to release common-2");
            int startedClient = output.indexOf("updating tag client to release client-1");
            assertTrue(completedCommon >= 0 && startedClient > completedCommon,
                    "the first tag finishes before the next starts: " + output);
        }
    }

    @Test
    void aFailureInTheFirstTagStopsBeforeRequestingTheNextTag(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, "common", "1", "common.txt", "common");
            publish(publisher, TAG, "1", "client.txt", "client");
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            s3.fail("GET", TestWorkspace.bucketPath(ObjectKeys.latest("common")), 403);
            int before = s3.requests().size();

            assertEquals(Cli.FAILURE, run(player, "update", "--tag", "common", "--tag", TAG), logs.errText());

            assertTrue(logs.errText().contains("updating tag common failed"), logs.errText());
            assertTrue(logs.errText().contains("access denied"), logs.errText());
            assertEquals(List.of(get(ObjectKeys.latest("common"))), requestsSince(s3, before));
            assertFalse(Files.exists(Layout.currentFile(player, "common")));
            assertFalse(Files.exists(Layout.currentFile(player, TAG)));
            assertFalse(Files.exists(player.resolve("common.txt")));
            assertFalse(Files.exists(player.resolve("client.txt")));
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
            assertEquals(Cli.OK, run(player, "update", "--tag", TAG), logs.errText());
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
            assertFalse(Files.exists(Layout.currentFile(player, "server")));
            assertFalse(Files.exists(player.resolve("server.txt")));
            assertEquals(List.of(get(ObjectKeys.latest("common")), get(ObjectKeys.latest(TAG))),
                    latestRequests(s3, before));
            String serverPrefix = TestWorkspace.bucketPath(ObjectKeys.METADATA + "/server/");
            assertTrue(requestsSince(s3, before).stream().noneMatch(request -> request.contains(serverPrefix)),
                    requestsSince(s3, before).toString());
        }
    }

    @Test
    void aFailedBatchResumesWithoutReapplyingItsSuccessfulTags(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, "common", "1", "common.txt", "common");
            publish(publisher, TAG, "1", "client.txt", "client");
            publish(publisher, "server", "1", "server.txt", "server");
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            s3.fail("GET", TestWorkspace.bucketPath(ObjectKeys.latest(TAG)), 403);
            int failedBefore = s3.requests().size();
            assertEquals(Cli.FAILURE, run(player, "update", "--tag", "common", "--tag", TAG, "--tag", "server"),
                    logs.errText());
            assertEquals("1", Pointer.read(Layout.currentFile(player, "common")).releaseId());
            assertFalse(Files.exists(Layout.currentFile(player, TAG)));
            assertFalse(Files.exists(Layout.currentFile(player, "server")));
            assertFalse(Files.exists(player.resolve("client.txt")));
            assertFalse(Files.exists(player.resolve("server.txt")));
            assertEquals(List.of(get(ObjectKeys.latest("common")), get(ObjectKeys.latest(TAG))),
                    latestRequests(s3, failedBefore));
            s3.clearFailures();
            int before = s3.requests().size();

            assertEquals(Cli.OK, run(player, "update", "--tag", "common", "--tag", TAG, "--tag", "server"),
                    logs.errText());

            assertEquals("1", Pointer.read(Layout.currentFile(player, "common")).releaseId());
            assertEquals("1", Pointer.read(Layout.currentFile(player, TAG)).releaseId());
            assertEquals("1", Pointer.read(Layout.currentFile(player, "server")).releaseId());
            assertEquals("common", Files.readString(player.resolve("common.txt")));
            assertEquals("client", Files.readString(player.resolve("client.txt")));
            assertEquals("server", Files.readString(player.resolve("server.txt")));
            assertEquals(List.of(get(ObjectKeys.latest("common")), get(ObjectKeys.latest(TAG)),
                    get(ObjectKeys.latest("server"))), latestRequests(s3, before));
            assertFalse(requestsSince(s3, before).contains(get(ObjectKeys.release("common", "1"))),
                    "an already committed tag does not fetch its manifest again");
            assertTrue(logs.outText().contains("tag common is already at release 1"), logs.outText());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBatches")
    void rejectsAnInvalidBatchBeforeUpdatingAnyTag(String description, int exitCode, String fragment,
            List<String> args, @TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, "common", "1", "common.txt", "common");
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            int before = s3.requests().size();

            assertEquals(exitCode, run(player, args.toArray(String[]::new)), logs.errText());

            assertTrue(logs.errText().contains(fragment), logs.errText());
            assertEquals(List.of(), requestsSince(s3, before), "validation precedes every tag update");
            assertFalse(Files.exists(Layout.currentFile(player, "common")));
            assertFalse(Files.exists(Layout.currentFile(player, TAG)));
            assertFalse(Files.exists(player.resolve("common.txt")));
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

            assertEquals(Cli.OK, run(player, "update", "--tag", TAG, "--tag", TAG, "--id", "1"), logs.errText());

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
        assertFalse(Files.exists(Layout.baseDir(selected, "common")));
        assertFalse(Files.exists(ignored));
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
    void updateTakesTheReleaseIdToApplyThroughTheCli(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            TestFiles.write(Layout.baseDir(publisher, TAG), "mods/a.jar", TestFiles.bytes("A"));
            assertEquals(Cli.OK, run(publisher, "publish", "--tag", TAG, "--id", "2.9.0"), logs.errText());
            TestFiles.write(Layout.baseDir(publisher, TAG), "mods/a.jar", TestFiles.bytes("A2"));
            assertEquals(Cli.OK, run(publisher, "publish", "--tag", TAG, "--id", "2.9.1"), logs.errText());

            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            assertEquals(Cli.OK, run(root, "update", "--tag", TAG, "--id", "2.9.0",
                    "--workspace", player.toString()), logs.errText());

            assertEquals("A", Files.readString(player.resolve("mods/a.jar")),
                    "the named release, not the latest one, was applied");
            assertTrue(logs.outText().contains("to release 2.9.0"), logs.outText());
        }
    }

    @Test
    void initLaysOutAWorkspace(@TempDir Path cwd) {
        assertEquals(Cli.OK, run(cwd, "init", "--tag", TAG), logs.errText());

        assertTrue(Files.isRegularFile(Layout.configFile(cwd)));
        assertTrue(Files.isDirectory(Layout.baseDir(cwd, TAG)));
        assertTrue(logs.outText().contains("created "), logs.outText());
        assertTrue(logs.outText().contains("workspace is ready"), logs.outText());
    }

    @Test
    void dryRunIsNoLongerAnOption(@TempDir Path cwd) {
        assertUsage(cwd, "unknown option: --dry-run", "update", "--tag", TAG, "--dry-run");
    }

    @Test
    void aFailedCommandReportsAnErrorAndExitsWithFailure(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            TestFiles.write(Layout.baseDir(publisher, TAG), "mods/a.jar", TestFiles.bytes("A"));
            assertEquals(Cli.OK, run(publisher, "publish", "--tag", TAG, "--id", "2.9.0"), logs.errText());
            String latest = TestWorkspace.bucketPath(ObjectKeys.latest(TAG));
            String before = s3.text(latest);
            s3.fail("PUT", latest, 500);

            assertEquals(Cli.FAILURE, run(publisher, "publish", "--tag", TAG, "--id", "2.9.1"),
                    logs.errText());

            assertTrue(logs.errText().contains(
                    "ERROR publishing release 2.9.1 of tag client failed: "), logs.errText());
            assertTrue(logs.errText().contains("HTTP 500"), logs.errText());
            assertEquals(before, s3.text(latest), "the pointer the failed run did not reach is unchanged");
            assertEquals("2.9.0", Pointer.parse(before, "latest").releaseId());
        }
    }

    @Test
    void updateOutsideAWorkspaceIsAFailureNotAUsageError(@TempDir Path cwd) {
        assertEquals(Cli.FAILURE, run(cwd, "update", "--tag", TAG));

        assertTrue(logs.errText().contains("ERROR updating tag client failed: "), logs.errText());
        assertTrue(logs.errText().contains("config"), logs.errText());
    }
}

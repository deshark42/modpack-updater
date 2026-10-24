package io.github.deshark42.modpackupdater.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.LogSink;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.log.Log;
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

    private LogSink sink = new LogSink();

    private int run(Path cwd, String... args) {
        sink = new LogSink();
        return Cli.run(args, cwd, sink.out(), sink.err());
    }

    private void assertUsage(Path cwd, String fragment, String... args) {
        assertEquals(Cli.USAGE_ERROR, run(cwd, args), sink.errText());
        assertTrue(sink.errText().contains(fragment), sink.errText());
    }

    /** Command lines that the parser has to refuse, and the hint it has to print. */
    private static Stream<Arguments> optionUsageErrors() {
        return Stream.of(
                Arguments.of("update without --tag", "needs --tag", List.of("update")),
                Arguments.of("publish without --id", "needs --id", List.of("publish", "--tag", TAG)),
                Arguments.of("init with --id", "init does not take --id",
                        List.of("init", "--tag", TAG, "--id", "1.0")),
                Arguments.of("option without a value", "needs a value", List.of("update", "--tag")));
    }

    /** Values or commands that a --cache-max-age-days option cannot appear with. */
    private static Stream<Arguments> badCacheMaxAge() {
        return Stream.of(
                Arguments.of("publish with zero days", "cache-max-age-days needs a positive number of days",
                        List.of("publish", "--tag", TAG, "--id", "2.9.0", "--dry-run",
                                "--cache-max-age-days", "0")),
                Arguments.of("publish with a word", "cache-max-age-days needs a positive number of days",
                        List.of("publish", "--tag", TAG, "--id", "2.9.0", "--dry-run",
                                "--cache-max-age-days", "soon")),
                Arguments.of("update with the option", "update does not take --cache-max-age-days",
                        List.of("update", "--tag", TAG, "--cache-max-age-days", "7")),
                Arguments.of("init with the option", "init does not take --cache-max-age-days",
                        List.of("init", "--cache-max-age-days", "7")));
    }

    @Test
    void noCommandIsAUsageError(@TempDir Path cwd) {
        assertEquals(Cli.USAGE_ERROR, run(cwd));
        assertTrue(sink.errText().contains("usage: modpack-updater"), sink.errText());
    }

    @Test
    void unknownOptionIsAUsageError(@TempDir Path cwd) {
        assertUsage(cwd, "unknown option: --wat", "update", "--tag", TAG, "--wat");
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
        TestFiles.write(Layout.baseDir(cwd, TAG), "mods/a.jar", TestFiles.bytes("A"));

        assertEquals(Cli.OK, run(cwd, "publish", "--tag", TAG, "--id", "2.9.0", "--dry-run",
                "--cache-max-age-days", "7"), sink.errText());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badCacheMaxAge")
    void rejectsACacheMaxAgeItCannotUse(String description, String fragment, List<String> args,
            @TempDir Path cwd) {
        assertUsage(cwd, fragment, args.toArray(String[]::new));
    }

    @Test
    void dryRunPublishNeedsNoConnection(@TempDir Path cwd) throws IOException {
        TestFiles.write(Layout.baseDir(cwd, TAG), "mods/a.jar", TestFiles.bytes("A"));

        assertEquals(Cli.OK, run(cwd, "publish", "--tag", TAG, "--id", "2.9.0", "--dry-run"));

        assertTrue(sink.outText().lines().anyMatch(line ->
                line.matches("dry run: nothing was written, \\d+\\.\\ds")), sink.outText());
    }

    @Test
    void publishesAndUpdatesThroughTheCli(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            TestFiles.write(Layout.baseDir(publisher, TAG), "mods/a.jar", TestFiles.bytes("A"));

            assertEquals(Cli.OK, run(publisher, "publish", "--tag", TAG, "--id", "2.9.0"), sink.errText());

            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            assertEquals(Cli.OK, run(root, "update", "--tag", TAG, "--workspace", player.toString()),
                    sink.errText());

            assertEquals("A", Files.readString(player.resolve("mods/a.jar")));
            assertTrue(sink.outText().contains("is now at 2.9.0"), sink.outText());
        }
    }

    @Test
    void updateTakesTheReleaseIdToApplyThroughTheCli(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            TestFiles.write(Layout.baseDir(publisher, TAG), "mods/a.jar", TestFiles.bytes("A"));
            assertEquals(Cli.OK, run(publisher, "publish", "--tag", TAG, "--id", "2.9.0"), sink.errText());
            TestFiles.write(Layout.baseDir(publisher, TAG), "mods/a.jar", TestFiles.bytes("A2"));
            assertEquals(Cli.OK, run(publisher, "publish", "--tag", TAG, "--id", "2.9.1"), sink.errText());

            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            assertEquals(Cli.OK, run(root, "update", "--tag", TAG, "--id", "2.9.0",
                    "--workspace", player.toString()), sink.errText());

            assertEquals("A", Files.readString(player.resolve("mods/a.jar")),
                    "the named release, not the latest one, was applied");
            assertTrue(sink.outText().contains("is now at 2.9.0"), sink.outText());
        }
    }

    @Test
    void initLaysOutAWorkspace(@TempDir Path cwd) {
        assertEquals(Cli.OK, run(cwd, "init", "--tag", TAG), sink.errText());

        assertTrue(Files.isRegularFile(Layout.configFile(cwd)));
        assertTrue(Files.isDirectory(Layout.baseDir(cwd, TAG)));
        assertTrue(sink.outText().contains("created "), sink.outText());
        assertTrue(sink.outText().contains("workspace is ready"), sink.outText());
    }

    @Test
    void initDoesNotTakeDryRun(@TempDir Path cwd) {
        assertUsage(cwd, "init does not take --dry-run", "init", "--dry-run");
    }

    @Test
    void aFailedCommandReportsAnErrorAndExitsWithFailure(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            TestFiles.write(Layout.baseDir(publisher, TAG), "mods/a.jar", TestFiles.bytes("A"));
            assertEquals(Cli.OK, run(publisher, "publish", "--tag", TAG, "--id", "2.9.0"), sink.errText());
            String latest = TestWorkspace.bucketPath(ObjectKeys.latest(TAG));
            String before = s3.text(latest);
            s3.fail("PUT", latest, 500);

            assertEquals(Cli.FAILURE, run(publisher, "publish", "--tag", TAG, "--id", "2.9.1"),
                    sink.errText());

            assertTrue(sink.errText().startsWith("error: "), sink.errText());
            assertTrue(sink.errText().contains("HTTP 500"), sink.errText());
            assertEquals(before, s3.text(latest), "the pointer the failed run did not reach is unchanged");
            assertEquals("2.9.0", Pointer.parse(before, "latest").releaseId());
        }
    }

    @Test
    void updateOutsideAWorkspaceIsAFailureNotAUsageError(@TempDir Path cwd) {
        assertEquals(Cli.FAILURE, run(cwd, "update", "--tag", TAG));

        assertTrue(sink.errText().startsWith("error: "), sink.errText());
        assertTrue(sink.errText().contains("config"), sink.errText());
    }

    @Test
    void quietPrintsOnlyTheDryRunLine(@TempDir Path cwd) throws IOException {
        TestFiles.write(Layout.baseDir(cwd, TAG), "mods/a.jar", TestFiles.bytes("A"));

        assertEquals(Cli.OK, run(cwd, "publish", "--tag", TAG, "--id", "2.9.0", "--dry-run", "--quiet"));

        assertEquals(1, sink.outText().lines().count(), sink.outText());
        assertTrue(sink.outText().lines().anyMatch(line ->
                line.matches("dry run: nothing was written, \\d+\\.\\ds")), sink.outText());
    }

    @Test
    void verboseNamesEachScannedPath(@TempDir Path cwd) throws IOException {
        TestFiles.write(Layout.baseDir(cwd, TAG), "mods/a.jar", TestFiles.bytes("A"));

        assertEquals(Cli.OK, run(cwd, "publish", "--tag", TAG, "--id", "2.9.0", "--dry-run", "--verbose"));

        String base = Log.relativeTo(cwd, Layout.baseDir(cwd, TAG));
        assertTrue(sink.outText().lines().anyMatch(line ->
                line.stripLeading().startsWith("scan") && line.contains(base)), sink.outText());
    }

    @Test
    void verboseAndQuietCannotBeCombined(@TempDir Path cwd) {
        assertUsage(cwd, "cannot be combined", "update", "--tag", TAG, "--verbose", "--quiet");
    }
}

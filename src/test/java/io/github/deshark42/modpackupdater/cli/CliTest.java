package io.github.deshark42.modpackupdater.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.log.Text;
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
    void noCommandIsAUsageError(@TempDir Path cwd) {
        assertEquals(Cli.USAGE_ERROR, run(cwd));
        assertTrue(logs.errText().contains("usage: modpack-updater"), logs.errText());
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
            assertTrue(logs.outText().contains("is now at 2.9.0"), logs.outText());
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
            assertTrue(logs.outText().contains("is now at 2.9.0"), logs.outText());
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

            assertTrue(logs.errText().startsWith("error: "), logs.errText());
            assertTrue(logs.errText().contains("HTTP 500"), logs.errText());
            assertEquals(before, s3.text(latest), "the pointer the failed run did not reach is unchanged");
            assertEquals("2.9.0", Pointer.parse(before, "latest").releaseId());
        }
    }

    @Test
    void updateOutsideAWorkspaceIsAFailureNotAUsageError(@TempDir Path cwd) {
        assertEquals(Cli.FAILURE, run(cwd, "update", "--tag", TAG));

        assertTrue(logs.errText().startsWith("error: "), logs.errText());
        assertTrue(logs.errText().contains("config"), logs.errText());
    }

    @Test
    void quietPrintsOnlyTheClosingLine(@TempDir Path cwd) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(cwd, s3);
            TestFiles.write(Layout.baseDir(cwd, TAG), "mods/a.jar", TestFiles.bytes("A"));

            assertEquals(Cli.OK, run(cwd, "publish", "--tag", TAG, "--id", "2.9.0", "--quiet"));

            assertEquals(1, logs.outText().lines().count(), logs.outText());
            assertTrue(logs.outText().contains("is now the latest"), logs.outText());
        }
    }

    @Test
    void verboseNamesEachScannedPath(@TempDir Path cwd) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(cwd, s3);
            TestFiles.write(Layout.baseDir(cwd, TAG), "mods/a.jar", TestFiles.bytes("A"));

            assertEquals(Cli.OK, run(cwd, "publish", "--tag", TAG, "--id", "2.9.0", "--verbose"));

            String base = Text.relativeTo(cwd, Layout.baseDir(cwd, TAG));
            assertTrue(logs.outText().lines().anyMatch(line -> line.contains("scan " + base)), logs.outText());
        }
    }

    @Test
    void verboseAndQuietCannotBeCombined(@TempDir Path cwd) {
        assertUsage(cwd, "cannot be combined", "update", "--tag", TAG, "--verbose", "--quiet");
    }
}

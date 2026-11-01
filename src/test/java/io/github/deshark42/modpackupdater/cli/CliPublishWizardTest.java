package io.github.deshark42.modpackupdater.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestArchives;
import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.TestSigning;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.model.ChannelHistory;
import io.github.deshark42.modpackupdater.model.FileEntry;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import io.github.deshark42.modpackupdater.model.Pointer;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@ResourceLock(TestLogs.RESOURCE)
class CliPublishWizardTest {
    private static final String TAG = "client";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2024-01-02T03:04:05Z"), ZoneOffset.UTC);

    private record Result(int exitCode, String out, String err) {
    }

    private static Result run(Path cwd, Reader input, String... args) {
        try (TestLogs logs = new TestLogs()) {
            int exitCode = Cli.run(args, cwd, logs.out(), logs.err(), CLOCK, input);
            return new Result(exitCode, logs.outText(), logs.errText());
        }
    }

    private static String key(String objectKey) {
        return TestWorkspace.bucketPath(objectKey);
    }

    private static Reader unreadableInput() {
        return new Reader() {
            @Override
            public int read(char[] buffer, int offset, int length) {
                throw new AssertionError("this command must not read interactive input");
            }

            @Override
            public void close() {
                throw new AssertionError("the CLI does not own its input");
            }
        };
    }

    private static void seedBaseline(FakeS3 s3) {
        String oldHash = Sha256.ofBytes(TestFiles.bytes("old"));
        Manifest baseline = new Manifest(TAG, "previous", CLOCK.instant(), Manifest.SHA256,
                Map.of("client.txt", new FileEntry(oldHash, 3, oldHash),
                        "gone.txt", new FileEntry(oldHash, 3, oldHash)));
        byte[] bytes = TestFiles.bytes(baseline.toJson());
        s3.put(key(ObjectKeys.release(TAG, "previous")), bytes);
        s3.put(key(ObjectKeys.signature(TAG, "previous")), TestSigning.sign(bytes));
        s3.put(key(ObjectKeys.channel(TAG, "beta")), TestFiles.bytes(new Pointer("previous").toJson()));
    }

    @Test
    void aConfirmedWizardPublishesTheSelectedWorkspaceAndChannel(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            TestWorkspace.base(publisher, TAG, "client.txt", "interactive content");
            Files.createDirectories(Layout.baseDir(publisher, "server"));
            byte[] stable = TestFiles.bytes(new Pointer("stable").toJson());
            s3.put(key(ObjectKeys.latest(TAG)), stable);

            seedBaseline(s3);
            TestWorkspace.base(publisher, TAG, "added.txt", "new content");
            Result result;
            try (TestLogs logs = new TestLogs()) {
                String answers = "publisher\n1\nn\nbeta\ncandidate\n";
                Reader terminal = new StringReader(answers) {
                    private int consumed;

                    @Override
                    public int read(char[] buffer, int offset, int length) throws IOException {
                        if (consumed == answers.indexOf("candidate")) {
                            assertTrue(logs.outText().contains("Latest release id on channel beta: previous"));
                            assertTrue(logs.outText().contains("  + added.txt"));
                            assertTrue(logs.outText().contains("  ~ client.txt"));
                            assertTrue(logs.outText().contains("  - gone.txt"));
                            assertTrue(logs.outText().contains("New release id to publish"));
                            assertTrue(s3.requests().stream().noneMatch(request -> request.startsWith("PUT ")));
                            assertFalse(Files.exists(Layout.logsDir(publisher)));
                            assertFalse(Files.exists(Layout.knownBlobsFile(publisher)));
                            assertFalse(Files.exists(Layout.channelHistoryFile(publisher, TAG)));
                        }
                        int count = super.read(buffer, offset, Math.min(length, 1));
                        if (count > 0) {
                            consumed += count;
                        }
                        return count;
                    }
                };
                int exitCode = Cli.run(new String[] {"publish"}, root, logs.out(), logs.err(), CLOCK, terminal);
                result = new Result(exitCode, logs.outText(), logs.errText());
            }

            assertEquals(Cli.OK, result.exitCode(), result.err());
            assertEquals("", result.err());
            assertEquals(new Pointer("candidate"), Pointer.parse(s3.text(key(ObjectKeys.channel(TAG, "beta"))), "beta"));
            assertArrayEquals(stable, s3.object(key(ObjectKeys.latest(TAG))));
            Manifest manifest = Manifest.parse(s3.text(key(ObjectKeys.release(TAG, "candidate"))), "release");
            assertEquals(TAG, manifest.tag());
            assertEquals("candidate", manifest.releaseId());
            assertEquals(Set.of("client.txt", "added.txt"), manifest.files().keySet());
            assertEquals(64, s3.object(key(ObjectKeys.signature(TAG, "candidate"))).length);
            assertTrue(result.out().contains(publisher.toString()), result.out());
            assertTrue(Files.isDirectory(Layout.logsDir(publisher)));
            assertTrue(Files.isRegularFile(Layout.knownBlobsFile(publisher)));
            assertFalse(Files.exists(root.resolve(Layout.STATE_DIR)), "the selected workspace owns all side effects");
            assertEquals(List.of("latest", "beta"), ChannelHistory.channels(publisher, TAG));
            int beforeNextRun = s3.requests().size();

            Result nextRun = run(publisher, new StringReader("2\n:q\n"), "publish",
                    "--workspace", publisher.toString(), "--tag", TAG);

            assertEquals(Cli.OK, nextRun.exitCode(), nextRun.err());
            assertTrue(nextRun.out().contains("2) beta"));
            assertTrue(nextRun.out().contains("Latest release id on channel beta: candidate"));
            assertEquals(new Pointer("candidate"), Pointer.parse(s3.text(key(ObjectKeys.channel(TAG, "beta"))), "beta"));
            assertTrue(s3.requests().subList(beforeNextRun, s3.requests().size()).stream()
                    .noneMatch(request -> request.startsWith("PUT ")));
            assertEquals(List.of("latest", "beta"), ChannelHistory.channels(publisher, TAG));
        }
    }

    @Test
    void partiallySuppliedOptionsCanPublishWithoutUpdatingAChannel(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "client.txt", "pointerless content");

            Result result = run(workspace, new StringReader("candidate\n"), "publish",
                    "--workspace", workspace.toString(), "--tag", TAG, "--no-latest", "--cache-max-age-days", "7");

            assertEquals(Cli.OK, result.exitCode(), result.err());
            assertEquals("candidate", Manifest.parse(s3.text(key(ObjectKeys.release(TAG, "candidate"))), "release").releaseId());
            assertEquals(64, s3.object(key(ObjectKeys.signature(TAG, "candidate"))).length);
            assertNull(s3.object(key(ObjectKeys.latest(TAG))));
            assertFalse(Files.exists(Layout.channelHistoryFile(workspace, TAG)));
            assertFalse(s3.requests().stream().anyMatch(request -> request.endsWith(" " + key(ObjectKeys.latest(TAG)))));
        }
    }

    private static Stream<Arguments> cancellations() {
        return Stream.of(
                Arguments.of("EOF before workspace selection", ""),
                Arguments.of("explicit cancellation at workspace", ":q\n"),
                Arguments.of("EOF at tag selection", "publisher\n"),
                Arguments.of("blank release id", "publisher\n\nbeta\n\n"),
                Arguments.of("quit after preview", "publisher\n\nbeta\n:q\n"),
                Arguments.of("EOF after preview", "publisher\n\nbeta\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cancellations")
    void anUnconfirmedWizardLeavesBothWorkspaceAndStoreUntouched(String description, String answers,
            @TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            TestWorkspace.base(publisher, TAG, "client.txt", "unchanged content");
            byte[] config = Files.readAllBytes(Layout.configFile(publisher));
            byte[] latest = TestFiles.bytes(new Pointer("stable").toJson());
            s3.put(key(ObjectKeys.latest(TAG)), latest);
            seedBaseline(s3);

            Result result = run(root, new StringReader(answers), "publish");

            assertEquals(Cli.OK, result.exitCode(), result.err());
            assertTrue(result.out().contains("cancelled"), result.out());
            assertEquals("", result.err());
            assertTrue(s3.requests().stream().allMatch(request -> request.startsWith("HEAD ") || request.startsWith("GET ")),
                    "only baseline reads are allowed before confirmation");
            if (result.out().contains("Diff (")) {
                assertTrue(result.out().contains("Latest release id on channel beta: previous"));
                assertTrue(s3.requests().contains("GET " + key(ObjectKeys.release(TAG, "previous"))));
            } else {
                assertEquals(List.of(), s3.requests());
            }
            assertEquals(4, s3.objectCount());
            assertArrayEquals(latest, s3.object(key(ObjectKeys.latest(TAG))));
            assertArrayEquals(config, Files.readAllBytes(Layout.configFile(publisher)));
            assertEquals("unchanged content", Files.readString(Layout.baseDir(publisher, TAG).resolve("client.txt")));
            assertFalse(Files.exists(Layout.logsDir(publisher)));
            assertFalse(Files.exists(Layout.knownBlobsFile(publisher)));
            assertFalse(Files.exists(Layout.channelHistoryFile(publisher, TAG)));
            assertFalse(Files.exists(Layout.tempDir(publisher)));
            assertFalse(Files.exists(root.resolve(Layout.STATE_DIR)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"blank", "quit", "disconnect"})
    void anInterruptedArchivePreviewCleansItsStageWithoutPublishing(String interruption,
            @TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            Path archive = TestArchives.write(Layout.basePackDir(workspace, TAG).resolve("input.zip"),
                    Map.of("packed.txt", TestFiles.bytes("preview only")));
            seedBaseline(s3);
            byte[] config = Files.readAllBytes(Layout.configFile(workspace));
            byte[] originalArchive = Files.readAllBytes(archive);
            Reader input = interruption.equals("disconnect") ? new Reader() {
                @Override
                public int read(char[] buffer, int offset, int length) throws IOException {
                    throw new IOException("terminal disconnected after preview");
                }

                @Override
                public void close() {
                    throw new AssertionError("the CLI does not own its input");
                }
            } : new StringReader(interruption.equals("blank") ? "\n" : ":q\n");

            Result result = run(workspace, input, "publish", "--workspace", workspace.toString(),
                    "--tag", TAG, "--channel", "beta");

            assertEquals(interruption.equals("disconnect") ? Cli.FAILURE : Cli.OK, result.exitCode(), result.err());
            assertTrue(result.out().contains("Latest release id on channel beta: previous"));
            assertTrue(result.out().contains("  + packed.txt"));
            if (interruption.equals("disconnect")) {
                assertTrue(result.err().contains("terminal disconnected after preview"));
            } else {
                assertTrue(result.out().contains("cancelled"));
                assertEquals("", result.err());
            }
            assertTrue(s3.requests().stream().allMatch(request -> request.startsWith("HEAD ") || request.startsWith("GET ")));
            assertEquals(3, s3.objectCount());
            assertArrayEquals(config, Files.readAllBytes(Layout.configFile(workspace)));
            assertArrayEquals(originalArchive, Files.readAllBytes(archive));
            assertFalse(Files.exists(Layout.logsDir(workspace)));
            assertFalse(Files.exists(Layout.knownBlobsFile(workspace)));
            try (var stages = Files.list(Layout.tempDir(workspace))) {
                assertEquals(List.of(), stages.toList());
            }
        }
    }

    @Test
    void aBrokenBaselineReportsFailureBeforeAskingForAReleaseId(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "client.txt", "never published");
            seedBaseline(s3);
            s3.put(key(ObjectKeys.signature(TAG, "previous")), new byte[64]);
            byte[] config = Files.readAllBytes(Layout.configFile(workspace));

            Result result = run(workspace, unreadableInput(), "publish", "--workspace", workspace.toString(),
                    "--tag", TAG, "--channel", "beta");

            assertEquals(Cli.FAILURE, result.exitCode());
            assertTrue(result.err().contains("signature"), result.err());
            assertFalse(result.out().contains("New release id to publish"));
            assertTrue(s3.requests().stream().noneMatch(request -> request.startsWith("PUT ")));
            assertEquals(3, s3.objectCount());
            assertArrayEquals(config, Files.readAllBytes(Layout.configFile(workspace)));
            assertFalse(Files.exists(Layout.logsDir(workspace)));
            assertFalse(Files.exists(Layout.knownBlobsFile(workspace)));
            assertFalse(Files.exists(Layout.tempDir(workspace)));
        }
    }

    @Test
    void aFullySpecifiedPublishNeverReadsTerminalInput(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "client.txt", "scripted content");

            Result result = run(workspace, unreadableInput(), "publish", "--tag", TAG, "--id", "scripted");

            assertEquals(Cli.OK, result.exitCode(), result.err());
            assertEquals(new Pointer("scripted"), Pointer.parse(s3.text(key(ObjectKeys.latest(TAG))), "latest"));
        }
    }

    @Test
    void anIncompletePublishWithoutATerminalFailsWithoutCreatingFiles(@TempDir Path workspace) throws IOException {
        Result result = run(workspace, null, "publish");

        assertEquals(Cli.USAGE_ERROR, result.exitCode());
        assertTrue(result.err().contains("without a terminal"), result.err());
        assertEquals("", result.out());
        try (var entries = Files.list(workspace)) {
            assertEquals(List.of(), entries.toList());
        }
    }

    @Test
    void aDisconnectedTerminalReportsFailureWithoutStartingPublication(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "client.txt", "unchanged content");
            byte[] config = Files.readAllBytes(Layout.configFile(workspace));
            Reader disconnected = new Reader() {
                @Override
                public int read(char[] buffer, int offset, int length) throws IOException {
                    throw new IOException("terminal disconnected");
                }

                @Override
                public void close() {
                    throw new AssertionError("the CLI does not own its input");
                }
            };

            Result result = run(workspace, disconnected, "publish", "--workspace", workspace.toString(), "--tag", TAG);

            assertEquals(Cli.FAILURE, result.exitCode());
            assertTrue(result.err().contains("publish failed"), result.err());
            assertTrue(result.err().contains("terminal disconnected"), result.err());
            assertEquals(List.of(), s3.requests());
            assertEquals(0, s3.objectCount());
            assertArrayEquals(config, Files.readAllBytes(Layout.configFile(workspace)));
            assertFalse(Files.exists(Layout.logsDir(workspace)));
            assertFalse(Files.exists(Layout.knownBlobsFile(workspace)));
            assertFalse(Files.exists(Layout.tempDir(workspace)));
        }
    }

    @Test
    void conflictingPublishOptionsFailBeforeAnyInteractiveRead(@TempDir Path workspace) throws IOException {
        Result result = run(workspace, unreadableInput(), "publish", "--channel", "beta", "--no-latest");

        assertEquals(Cli.USAGE_ERROR, result.exitCode());
        assertTrue(result.err().contains("cannot combine"), result.err());
        assertEquals("", result.out());
        try (var entries = Files.list(workspace)) {
            assertEquals(List.of(), entries.toList());
        }
    }
}

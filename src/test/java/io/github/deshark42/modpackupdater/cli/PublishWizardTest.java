package io.github.deshark42.modpackupdater.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.github.deshark42.modpackupdater.command.Publish;
import io.github.deshark42.modpackupdater.model.ChannelHistory;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.Pointer;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@ResourceLock(TestLogs.RESOURCE)
class PublishWizardTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2024-01-02T03:04:05Z"), ZoneOffset.UTC);

    private record Interaction(PublishWizard.Selection selection, String output) {
    }

    private static void configure(Path workspace) throws IOException {
        TestWorkspace.configure(workspace, "http://127.0.0.1:9", null);
        Files.createDirectories(Layout.baseDir(workspace, "client"));
    }

    private static Map<String, String> supplied() {
        return Map.of("workspace", ".", "tag", "client", "id", "1.0", "no-latest", "true",
                "cache-max-age-days", "7");
    }

    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> snapshot = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                String value = Files.isSymbolicLink(path) ? "link:" + Files.readSymbolicLink(path)
                        : Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "directory"
                        : Base64.getEncoder().encodeToString(Files.readAllBytes(path));
                snapshot.put(root.relativize(path).toString(), value);
            }
        }
        return snapshot;
    }

    private static PublishWizard.Selection collect(Path root, Path cwd, Map<String, String> options,
            BufferedReader input, PrintStream out) throws IOException {
        Map<String, String> before = snapshot(root);
        try {
            return PublishWizard.collect(cwd, options, input, out, CLOCK, (selection, prepared) -> {

            });
        } finally {
            assertEquals(before, snapshot(root), "plain-directory preview never mutates the filesystem");
        }
    }

    private static Interaction interact(Path root, Map<String, String> options, String answers) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var input = new BufferedReader(new StringReader(answers));
                var out = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            PublishWizard.Selection selection = collect(root, root, options, input, out);
            return new Interaction(selection, bytes.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void blankAnswersUseTheOnlyTagAndChannelBeforeReleaseIdConfirms(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(root, s3);
            Files.createDirectories(Layout.baseDir(root, "client"));

            Interaction result = interact(root, Map.of(), "\n\n\n1.0\n");

            assertEquals(new PublishWizard.Selection(root.toAbsolutePath(), "client", "1.0",
                    ObjectKeys.DEFAULT_CHANNEL, Publish.DEFAULT_CACHE_MAX_AGE_DAYS), result.selection());
            assertTrue(result.output().contains("Tag number [1]"));
            assertTrue(result.output().indexOf("Channel number [1]") < result.output().indexOf("Latest release id"));
            assertTrue(result.output().indexOf("Diff (") < result.output().indexOf("New release id to publish"));
            assertFalse(result.output().contains("Publish?"));
            assertFalse(result.output().contains("Blob cache max age (days) ["));
        }
    }

    @Test
    void anUnchangedPayloadStillRequiresANewValidReleaseIdAfterTheDiff(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(root, s3);
            Files.createDirectories(Layout.baseDir(root, "client"));
            var baseline = new Manifest("client", "previous", CLOCK.instant(),
                    Manifest.SHA256, Map.of());
            byte[] bytes = TestFiles.bytes(baseline.toJson());
            s3.put(TestWorkspace.bucketPath(ObjectKeys.channel("client", "beta")),
                    TestFiles.bytes(new Pointer("previous").toJson()));
            s3.put(TestWorkspace.bucketPath(ObjectKeys.release("client", "previous")), bytes);
            s3.put(TestWorkspace.bucketPath(ObjectKeys.signature("client", "previous")),
                    TestSigning.sign(bytes));
            Map<String, String> options = new HashMap<>(supplied());
            options.remove("id");
            options.remove("no-latest");
            options.put("channel", "beta");

            Interaction result = interact(root, options, "previous\nbad/id\nnext\n");

            assertEquals(new PublishWizard.Selection(root, "client", "next", "beta", 7), result.selection());
            assertTrue(result.output().contains("Latest release id on channel beta: previous"));
            assertTrue(result.output().contains("No file changes."));
            assertTrue(result.output().contains("enter a different release id from previous"));
            assertTrue(result.output().contains("invalid release id"));
            assertEquals(3, s3.objectCount());
            assertTrue(s3.requests().stream().noneMatch(request -> request.startsWith("PUT ")));
        }
    }

    @Test
    void enteredWorkspacesResolveRelativeToTheCallerDirectory(@TempDir Path root) throws IOException {
        Path workspace = root.resolve("pack");
        configure(workspace);
        Map<String, String> options = new HashMap<>(supplied());
        options.remove("workspace");

        Interaction result = interact(root, options, "pack/../pack\n1.0\n");

        assertEquals(new PublishWizard.Selection(workspace, "client", "1.0", null, 7), result.selection());
    }

    @ParameterizedTest(name = "channel answer {0}")
    @ValueSource(strings = {"beta", "none", "-"})
    void channelAnswersSelectNamedOrPointerlessReleases(String answer, @TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(root, s3);
            Files.createDirectories(Layout.baseDir(root, "client"));
            Map<String, String> options = new HashMap<>(supplied());
            options.remove("no-latest");

            Interaction result = interact(root, options, answer + "\n1.0\n");

            assertEquals(new PublishWizard.Selection(root, "client", "1.0", answer.equals("-") ? null : answer, 7),
                    result.selection());
            assertFalse(result.output().contains("Workspace ["));
            assertFalse(result.output().contains("Available tags"));
        }
    }

    private static Stream<Arguments> rememberedChoices() {
        return Stream.of(Arguments.of("", "latest"), Arguments.of("1", "latest"),
                Arguments.of("2", "2"), Arguments.of("3", "beta"), Arguments.of("4", "n"));
    }

    @ParameterizedTest(name = "remembered channel {0} selects {1}")
    @MethodSource("rememberedChoices")
    void aChannelNumberSelectsRememberedNamesWithoutChangingTheDefault(String answer, String channel,
            @TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(root, s3);
            Files.createDirectories(Layout.baseDir(root, "client"));
            ChannelHistory.remember(root, "client", "beta");
            ChannelHistory.remember(root, "client", "n");
            ChannelHistory.remember(root, "client", "2");
            Map<String, String> options = new HashMap<>(supplied());
            options.remove("no-latest");

            Interaction result = interact(root, options, answer + "\n1.0\n");

            assertEquals(channel, result.selection().channel());
            List<String> names = List.of("latest", "2", "beta", "n");
            for (int i = 0; i < names.size(); i++) {
                assertTrue(result.output().contains((i + 1) + ") " + names.get(i)), result.output());
            }
            assertTrue(result.output().contains("local history, not a server listing"));
            assertFalse(result.output().contains("New channel name"));
            assertEquals(List.of("HEAD " + TestWorkspace.bucketPath(ObjectKeys.channel("client", channel))),
                    s3.requests(), "only the selected channel is queried, not all remembered channels");
        }
    }

    @ParameterizedTest(name = "new channel name {0}")
    @ValueSource(strings = {"beta", "2", "n"})
    void aNewChannelNameIsNotInterpretedAsAMenuCommand(String name, @TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(root, s3);
            Files.createDirectories(Layout.baseDir(root, "client"));
            Map<String, String> options = new HashMap<>(supplied());
            options.remove("no-latest");

            Interaction result = interact(root, options, "n\n" + name + "\n1.0\n");

            assertEquals(name, result.selection().channel());
            assertTrue(result.output().contains("New channel name"));
            assertFalse(Files.exists(Layout.channelHistoryFile(root, "client")),
                    "selecting or previewing a new name is not successful publication");
        }
    }

    @Test
    void invalidChannelChoicesRetryBeforeANewNameIsAccepted(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(root, s3);
            Files.createDirectories(Layout.baseDir(root, "client"));
            Map<String, String> options = new HashMap<>(supplied());
            options.remove("no-latest");

            Interaction result = interact(root, options, "0\n9\n2147483648\nbad/name\nn\n\nbad/name\nbeta\n1.0\n");

            assertEquals("beta", result.selection().channel());
            assertTrue(result.output().contains("enter a channel number from 1 to 1"));
            assertTrue(result.output().contains("invalid channel"));
            assertTrue(result.output().contains("New channel name"));
        }
    }

    @ParameterizedTest(name = "new channel cancellation {0}")
    @ValueSource(strings = {"n\n", "n\n:q\n", "n\nbeta\n:q\n"})
    void cancellingANewChannelLeavesHistoryUntouched(String answers, @TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(root, s3);
            Files.createDirectories(Layout.baseDir(root, "client"));
            ChannelHistory.remember(root, "client", "nightly");
            Map<String, String> options = new HashMap<>(supplied());
            options.remove("no-latest");

            Interaction result = interact(root, options, answers);

            assertNull(result.selection());
            assertTrue(result.output().contains("cancelled"));
            assertEquals(List.of("latest", "nightly"), ChannelHistory.channels(root, "client"));
            assertTrue(s3.requests().stream().noneMatch(request -> request.startsWith("PUT ")));
        }
    }

    @Test
    void unreadableChannelHistoryWarnsAndStillAllowsAnExplicitName(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(root, s3);
            Files.createDirectories(Layout.baseDir(root, "client"));
            Path history = Layout.channelHistoryFile(root, "client");
            TestFiles.write(history.getParent(), history.getFileName().toString(), "corrupt history");
            Map<String, String> options = new HashMap<>(supplied());
            options.remove("no-latest");

            Interaction result = interact(root, options, "beta\n1.0\n");

            assertEquals("beta", result.selection().channel());
            assertTrue(result.output().contains("Warning: cannot read channel history"));
            assertTrue(result.output().contains("1) latest"));
            assertEquals("corrupt history", Files.readString(history));
        }
    }

    private static Stream<Arguments> retries() {
        return Stream.of(
                Arguments.of("workspace", "missing\nempty\n.\n1.0\n", "config is missing"),
                Arguments.of("tag", "client\n0\n-1\n3\n2147483648\n\n1\n1.0\n", "enter a tag number from 1 to 2"),
                Arguments.of("id", "bad/id\nq\n", "release id"),
                Arguments.of("supplied id", "different\n1.0\n", "supplied release id"));
    }

    @ParameterizedTest(name = "retries {0}")
    @MethodSource("retries")
    void invalidAnswersExplainAndRetryOnlyTheirPrompt(String missing, String answers, String fragment,
            @TempDir Path root) throws IOException {
        configure(root);
        Files.createDirectories(root.resolve("empty"));
        Files.createDirectories(Layout.basePackDir(root, "server"));
        Map<String, String> options = new HashMap<>(supplied());
        options.remove(missing);

        Interaction result = interact(root, options, answers);

        assertEquals(new PublishWizard.Selection(root, "client", missing.equals("id") ? "q" : "1.0", null, 7),
                result.selection());
        assertTrue(result.output().contains(fragment), result.output());
    }

    @Test
    void discoveryListsOnlySortedDirectTagsWithoutOpeningArchives(@TempDir Path root) throws IOException {
        configure(root);
        TestFiles.write(root, "zulu/" + Layout.BASE_PACK + "/unreadable.zip", "not an archive");
        Files.createDirectories(root.resolve("bad name/" + Layout.BASE));
        Files.createDirectories(root.resolve(".hidden/" + Layout.BASE));
        Files.createDirectories(root.resolve("nested/child/" + Layout.BASE));
        Files.createDirectories(root.resolve(Layout.STATE_DIR + "/" + Layout.BASE));
        Map<String, String> options = new HashMap<>(supplied());
        options.remove("tag");

        Interaction result = interact(root, options, ":q\n");

        assertNull(result.selection());
        assertTrue(result.output().contains("1) client"));
        assertTrue(result.output().contains("2) zulu"));
        assertFalse(result.output().contains("Tag number ["));
    }

    @ParameterizedTest(name = "tag number {0}")
    @ValueSource(ints = {1, 2, 3})
    void aTagNumberSelectsItsSortedListEntry(int number, @TempDir Path root) throws IOException {
        configure(root);
        Files.createDirectories(Layout.baseDir(root, "2"));
        Files.createDirectories(Layout.baseDir(root, "server"));
        Map<String, String> options = new HashMap<>(supplied());
        options.remove("tag");

        Interaction result = interact(root, options, number + "\n1.0\n");

        String expectedTag = java.util.List.of("2", "client", "server").get(number - 1);
        assertEquals(new PublishWizard.Selection(root, expectedTag, "1.0", null, 7), result.selection());
    }

    private static Stream<Arguments> cancellationPoints() {
        return Stream.of("", "\n", "\n\n", "\n\n-\n")
                .flatMap(prefix -> Stream.of(Arguments.of(prefix + "EOF", prefix),
                        Arguments.of(prefix + ":q", prefix + ":q\n")));
    }

    @ParameterizedTest(name = "cancels at {0}")
    @MethodSource("cancellationPoints")
    void endOfInputOrQuitCancelsAtEveryPrompt(String name, String answers, @TempDir Path root) throws IOException {
        configure(root);

        Interaction result = interact(root, Map.of(), answers);

        assertNull(result.selection());
        assertTrue(result.output().contains("cancelled"));
    }

    @Test
    void aBlankReleaseIdCancelsInsteadOfUsingASuppliedId(@TempDir Path root) throws IOException {
        configure(root);

        Interaction result = interact(root, supplied(), "\n");

        assertNull(result.selection());
        assertTrue(result.output().contains("cancelled"));
        assertTrue(result.output().contains("immutable"));
    }

    private static Stream<Arguments> invalidOptions() {
        return Stream.of(Arguments.of("tag", "../client", "invalid tag"),
                Arguments.of("id", "../1", "invalid release id"),
                Arguments.of("channel", "../beta", "cannot combine"),
                Arguments.of("cache-max-age-days", "0", "positive number"),
                Arguments.of("cache-max-age-days", "overflow", "positive number"),
                Arguments.of("workspace", "missing", "existing directory"),
                Arguments.of("workspace", "empty", "config is missing"),
                Arguments.of("workspace", "file", "existing directory"),
                Arguments.of("channel", "beta", "cannot combine"));
    }

    @ParameterizedTest(name = "rejects supplied {0}={1}")
    @MethodSource("invalidOptions")
    void invalidSuppliedOptionsFailBeforeReadingAnswers(String key, String value, String fragment,
            @TempDir Path root) throws IOException {
        configure(root);
        Files.createDirectories(root.resolve("empty"));
        TestFiles.write(root, "file", "not a directory");
        Map<String, String> options = new HashMap<>(supplied());
        options.put(key, value);
        try (var input = new BufferedReader(new StringReader("")) {
            @Override
            public String readLine() {
                throw new AssertionError("invalid supplied options must not read input");
            }
        }; var out = new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8)) {
            UserError error = assertThrows(UserError.class, () -> collect(root, root, options, input, out));
            assertTrue(error.getMessage().contains(fragment), error.getMessage());
        }
    }

    @Test
    void missingTagCandidatesGiveInitializationAdvice(@TempDir Path root) throws IOException {
        TestFiles.write(root, Layout.STATE_DIR + "/" + Layout.CONFIG, "existing config");

        UserError error = assertThrows(UserError.class, () -> interact(root, Map.of("workspace", "."), ""));

        assertTrue(error.getMessage().contains("no publisher tags"));
        assertTrue(error.getMessage().contains("init --tag"));
    }

    @Test
    void suppliedTagsNeedPublisherInputs(@TempDir Path root) throws IOException {
        configure(root);
        Map<String, String> options = new HashMap<>(supplied());
        options.put("tag", "missing");

        UserError error = assertThrows(UserError.class, () -> interact(root, options, "1.0\n"));

        assertTrue(error.getMessage().contains("base_pack"));
        assertTrue(error.getMessage().contains("init --tag missing"));
    }

    private static void link(Path path, Path target) throws IOException {
        try {
            Files.createSymbolicLink(path, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "symbolic links unavailable on this platform: " + e.getMessage());
        }
    }

    @Test
    void discoveryIgnoresLinkedTagsAndLinkedInputs(@TempDir Path root) throws IOException {
        configure(root);
        link(root.resolve("alias"), root.resolve("client"));
        Files.createDirectories(root.resolve("linked-input"));
        link(root.resolve("linked-input/" + Layout.BASE), Layout.baseDir(root, "client"));
        Map<String, String> options = new HashMap<>(supplied());
        options.remove("tag");

        Interaction result = interact(root, options, "\n1.0\n");

        assertEquals(new PublishWizard.Selection(root, "client", "1.0", null, 7), result.selection());
        assertTrue(result.output().contains("1) client"));
        assertFalse(result.output().contains("alias"));
        assertFalse(result.output().contains("linked-input"));
    }

    @Test
    void linkedConfigFailsThroughLayoutProtection(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve(Layout.STATE_DIR));
        TestFiles.write(root, "other-config", "config");
        link(root.resolve(Layout.STATE_DIR + "/" + Layout.CONFIG), root.resolve("other-config"));

        UserError error = assertThrows(UserError.class, () -> interact(root, supplied(), "1.0\n"));

        assertTrue(error.getMessage().contains("filesystem link or alias"));
    }

    @Test
    void inputFailuresBecomeUserErrorsWithoutRetrying(@TempDir Path root) throws IOException {
        configure(root);
        Map<String, String> options = new HashMap<>(supplied());
        options.remove("workspace");
        try (var input = new BufferedReader(new StringReader("")) {
            @Override
            public String readLine() throws IOException {
                throw new IOException("terminal disconnected");
            }
        }; var out = new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8)) {
            UserError error = assertThrows(UserError.class, () -> collect(root, root, options, input, out));
            assertTrue(error.getMessage().contains("publish wizard"));
            assertTrue(error.getMessage().contains("terminal disconnected"));
            assertEquals(IOException.class, error.getCause().getClass());
        }
    }

    @Test
    void promptsFlushAndLeaveCallerOwnedStreamsOpen(@TempDir Path root) throws IOException {
        configure(root);
        boolean[] flushed = {false};
        boolean[] closed = {false, false};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream() {
            @Override
            public void flush() {
                flushed[0] = true;
            }

            @Override
            public void close() {
                closed[1] = true;
            }
        };
        try (var input = new BufferedReader(new StringReader("\n\n-\n1.0\nremaining\n")) {
            @Override
            public String readLine() throws IOException {
                assertTrue(flushed[0], "every prompt flushes before reading");
                flushed[0] = false;
                return super.readLine();
            }

            @Override
            public void close() throws IOException {
                closed[0] = true;
                super.close();
            }
        }; var out = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
            PublishWizard.Selection selection = collect(root, root, Map.of(), input, out);
            assertEquals(new PublishWizard.Selection(root, "client", "1.0", null,
                    Publish.DEFAULT_CACHE_MAX_AGE_DAYS), selection);
            assertFalse(closed[0]);
            assertFalse(closed[1]);
            out.print("still open");
            out.flush();
            assertEquals("remaining", input.readLine());
        }
    }
}

package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ChannelHistoryTest {

    @Test
    void aMissingHistoryReturnsOnlyLatestWithoutCreatingState(@TempDir Path root) {
        List<String> channels = ChannelHistory.channels(root, "client");

        assertEquals(List.of(ObjectKeys.DEFAULT_CHANNEL), channels);
        assertFalse(Files.exists(Layout.stateDir(root)));
    }

    @Test
    void aMissingWorkspaceReadCreatesNothing(@TempDir Path root) {
        Path workspace = root.resolve("missing");

        List<String> channels = ChannelHistory.channels(workspace, "client");

        assertEquals(List.of(ObjectKeys.DEFAULT_CHANNEL), channels);
        assertFalse(Files.exists(workspace));
    }

    @Test
    void theLatestSuggestionPrecedesSortedUniqueRememberedNames(@TempDir Path root) throws Exception {
        Path file = history(root, Json.write(List.of("zeta", "beta", ObjectKeys.DEFAULT_CHANNEL, "Beta", "beta")));
        String before = Files.readString(file);

        List<String> channels = ChannelHistory.channels(root, "client");

        assertEquals(List.of(ObjectKeys.DEFAULT_CHANNEL, "Beta", "beta", "zeta"), channels);
        assertEquals(before, Files.readString(file));
    }

    @Test
    void aRememberedChannelCreatesItsTagHistory(@TempDir Path root) throws Exception {
        ChannelHistory.remember(root, "client", "beta");

        Path file = Layout.channelHistoryFile(root, "client");
        assertEquals(root.resolve(Layout.STATE_DIR).resolve("client").resolve(Layout.CHANNEL_HISTORY), file);
        assertEquals(List.of("beta"), Json.parse(Files.readString(file)));
        assertEquals(List.of(ObjectKeys.DEFAULT_CHANNEL, "beta"), ChannelHistory.channels(root, "client"));
    }

    @Test
    void aNewChannelWritesSortedUniqueNamesWithoutLatest(@TempDir Path root) throws Exception {
        Path file = history(root, Json.write(List.of("zeta", "beta", ObjectKeys.DEFAULT_CHANNEL, "beta")));

        ChannelHistory.remember(root, "client", "alpha");

        assertEquals(List.of("alpha", "beta", "zeta"), Json.parse(Files.readString(file)));
        try (var files = Files.list(file.getParent())) {
            assertEquals(List.of(file), files.toList());
        }
    }

    @Test
    void anAlreadyRememberedChannelLeavesTheFileUntouched(@TempDir Path root) throws Exception {
        String original = "  " + Json.write(List.of("zeta", "beta", "beta"));
        Path file = history(root, original);
        Files.setLastModifiedTime(file, FileTime.fromMillis(1000));
        FileTime modified = Files.getLastModifiedTime(file);

        ChannelHistory.remember(root, "client", "beta");

        assertEquals(original, Files.readString(file));
        assertEquals(modified, Files.getLastModifiedTime(file));
    }

    @ParameterizedTest(name = "remembering {0} creates no state")
    @NullSource
    @ValueSource(strings = ObjectKeys.DEFAULT_CHANNEL)
    void defaultAndPointerlessChannelsCreateNoHistory(String channel, @TempDir Path root) {
        ChannelHistory.remember(root, "client", channel);

        assertFalse(Files.exists(Layout.stateDir(root)));
    }

    @Test
    void rememberingLatestLeavesAnExistingHistoryUntouched(@TempDir Path root) throws Exception {
        String original = "broken history";
        Path file = history(root, original);

        ChannelHistory.remember(root, "client", ObjectKeys.DEFAULT_CHANNEL);

        assertEquals(original, Files.readString(file));
    }

    @Test
    void historiesStayWithinTheirWorkspaceAndTag(@TempDir Path root) throws Exception {
        Path first = Files.createDirectory(root.resolve("first"));
        Path second = Files.createDirectory(root.resolve("second"));
        ChannelHistory.remember(first, "client", "beta");
        ChannelHistory.remember(first, "server", "nightly");

        ChannelHistory.remember(second, "client", "preview");

        assertEquals(List.of(ObjectKeys.DEFAULT_CHANNEL, "beta"), ChannelHistory.channels(first, "client"));
        assertEquals(List.of(ObjectKeys.DEFAULT_CHANNEL, "nightly"), ChannelHistory.channels(first, "server"));
        assertEquals(List.of(ObjectKeys.DEFAULT_CHANNEL, "preview"), ChannelHistory.channels(second, "client"));
        assertEquals(List.of(ObjectKeys.DEFAULT_CHANNEL), ChannelHistory.channels(second, "server"));
        assertFalse(Files.exists(Layout.tagDir(second, "server")));
    }

    @ParameterizedTest(name = "{0} history is rejected on read")
    @MethodSource("invalidHistories")
    void malformedHistoryReadsFailWithContextWithoutChangingTheFile(String description, String text,
            String message, @TempDir Path root) throws Exception {
        Path file = history(root, text);

        UserError error = assertThrows(UserError.class, () -> ChannelHistory.channels(root, "client"));

        assertHistoryFailure(error, file, message);
        assertEquals(text, Files.readString(file));
    }

    @ParameterizedTest(name = "{0} history is not overwritten")
    @MethodSource("invalidHistories")
    void malformedHistoryCannotBeOverwrittenByANewChannel(String description, String text,
            String message, @TempDir Path root) throws Exception {
        Path file = history(root, text);

        UserError error = assertThrows(UserError.class, () -> ChannelHistory.remember(root, "client", "new"));

        assertHistoryFailure(error, file, message);
        assertEquals(text, Files.readString(file));
        try (var files = Files.list(file.getParent())) {
            assertEquals(List.of(file), files.toList());
        }
    }

    private static Stream<Arguments> invalidHistories() {
        return Stream.of(
                Arguments.of("empty", "", "malformed json"),
                Arguments.of("object", "{}", "expected an array"),
                Arguments.of("null", "null", "expected an array"),
                Arguments.of("number entry", "[12]", "entries must be strings"),
                Arguments.of("null entry", "[null]", "entries must be strings"),
                Arguments.of("boolean entry", "[true]", "entries must be strings"),
                Arguments.of("empty name", Json.write(List.of("")), "channel must not be empty"),
                Arguments.of("unsafe name", Json.write(List.of("../beta")), "invalid channel"),
                Arguments.of("long name", Json.write(List.of("b".repeat(121))), "longer than"),
                Arguments.of("trailing content", "[] []", "malformed json"),
                Arguments.of("comment", "/* comment */ []", "malformed json"));
    }

    @ParameterizedTest(name = "unsafe channel {0} leaves no state")
    @ValueSource(strings = {"", "../beta", "beta channel"})
    void unsafeNewChannelsAreRejectedBeforeCreatingHistory(String channel, @TempDir Path root) {
        UserError error = assertThrows(UserError.class, () -> ChannelHistory.remember(root, "client", channel));

        assertTrue(error.getMessage().contains("channel"), error.getMessage());
        assertFalse(Files.exists(Layout.stateDir(root)));
    }

    @Test
    void aHistoryFileLinkCannotRedirectARead(@TempDir Path root) throws Exception {
        Path file = Layout.channelHistoryFile(root, "client");
        Files.createDirectories(file.getParent());
        Path target = root.resolve("outside.json");
        String original = Json.write(List.of("beta"));
        Files.writeString(target, original);
        symbolicLink(file, target);

        UserError error = assertThrows(UserError.class, () -> ChannelHistory.channels(root, "client"));

        assertTrue(error.getMessage().contains("link or alias"), error.getMessage());
        assertTrue(Files.isSymbolicLink(file));
        assertEquals(original, Files.readString(target));
    }

    @Test
    void aTamperedTagDirectoryCannotRedirectARemember(@TempDir Path root) throws Exception {
        Path file = history(root, Json.write(List.of("beta")));
        Path moved = root.resolve("moved-state");
        Files.move(file.getParent(), moved);
        symbolicLink(file.getParent(), moved);
        String original = Files.readString(moved.resolve(Layout.CHANNEL_HISTORY));

        UserError error = assertThrows(UserError.class, () -> ChannelHistory.remember(root, "client", "nightly"));

        assertTrue(error.getMessage().contains("link or alias"), error.getMessage());
        assertTrue(Files.isSymbolicLink(file.getParent()));
        assertEquals(original, Files.readString(moved.resolve(Layout.CHANNEL_HISTORY)));
    }

    private static Path history(Path root, String text) throws IOException {
        Path file = Layout.channelHistoryFile(root, "client");
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    private static void assertHistoryFailure(UserError error, Path file, String message) {
        assertTrue(error.getMessage().contains("channel history"), error.getMessage());
        assertTrue(error.getMessage().contains(file.toString()), error.getMessage());
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    private static void symbolicLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links require filesystem support and permissions: " + e);
        }
    }
}

package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LayoutTest {

    @ParameterizedTest(name = "{0} resolves below its owning workspace")
    @MethodSource("workspacePaths")
    void eachPathUsesItsOwningWorkspace(String description, Function<Path, Path> resolve, Path relative,
            @TempDir Path root) {
        assertEquals(root.resolve(relative), resolve.apply(root));
    }

    private static Stream<Arguments> workspacePaths() {
        Path state = Path.of(Layout.STATE_DIR);
        Path tag = state.resolve("client");
        return Stream.of(
                Arguments.of("state", (Function<Path, Path>) Layout::stateDir, state),
                Arguments.of("config", (Function<Path, Path>) Layout::configFile, state.resolve(Layout.CONFIG)),
                Arguments.of("logs", (Function<Path, Path>) Layout::logsDir, state.resolve(Layout.LOGS)),
                Arguments.of("temp", (Function<Path, Path>) Layout::tempDir, state.resolve(Layout.TMP)),
                Arguments.of("temp blobs", (Function<Path, Path>) Layout::blobTempDir,
                        state.resolve(Layout.TMP).resolve(Layout.BLOB)),
                Arguments.of("temp files", (Function<Path, Path>) Layout::fileTempDir,
                        state.resolve(Layout.TMP).resolve(Layout.FILE)),
                Arguments.of("blob cache", (Function<Path, Path>) Layout::knownBlobsFile,
                        state.resolve(Layout.KNOWN_BLOBS)),
                Arguments.of("tag state", (Function<Path, Path>) root -> Layout.tagDir(root, "client"), tag),
                Arguments.of("current", (Function<Path, Path>) root -> Layout.currentFile(root, "client"),
                        tag.resolve(Layout.CURRENT)),
                Arguments.of("releases", (Function<Path, Path>) root -> Layout.releasesDir(root, "client"),
                        tag.resolve(Layout.RELEASES)),
                Arguments.of("release", (Function<Path, Path>) root -> Layout.releaseFile(root, "client", "2.9.0"),
                        tag.resolve(Layout.RELEASES).resolve("2.9.0.json")),
                Arguments.of("backups", (Function<Path, Path>) root -> Layout.backupsDir(root, "client"),
                        tag.resolve(Layout.BACKUP)),
                Arguments.of("backup", (Function<Path, Path>) root -> Layout.backupDir(root, "client", "2.9.0"),
                        tag.resolve(Layout.BACKUP).resolve("2.9.0")),
                Arguments.of("initial backup", (Function<Path, Path>) root -> Layout.initialBackupDir(root, "client"),
                        tag.resolve(Layout.BACKUP).resolve(Layout.INITIAL)),
                Arguments.of("tag root", (Function<Path, Path>) root -> Layout.tagRoot(root, "client"), Path.of("client")),
                Arguments.of("base", (Function<Path, Path>) root -> Layout.baseDir(root, "client"),
                        Path.of("client", Layout.BASE)),
                Arguments.of("base pack", (Function<Path, Path>) root -> Layout.basePackDir(root, "client"),
                        Path.of("client", Layout.BASE_PACK)),
                Arguments.of("overlay", (Function<Path, Path>) root -> Layout.overlayDir(root, "client"),
                        Path.of("client", Layout.OVERLAY)),
                Arguments.of("remove rules", (Function<Path, Path>) root -> Layout.removeFile(root, "client"),
                        Path.of("client", Layout.REMOVE)));
    }

    @ParameterizedTest(name = "{0} rejects unsafe names")
    @MethodSource("unsafePaths")
    void unsafeNamesCannotEscapeTheirLayout(String description, Function<Path, Path> resolve, String message,
            @TempDir Path root) {
        UserError error = assertThrows(UserError.class, () -> resolve.apply(root));

        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    private static Stream<Arguments> unsafePaths() {
        return Stream.of(
                Arguments.of("tag state", (Function<Path, Path>) root -> Layout.tagDir(root, "../client"), "invalid tag"),
                Arguments.of("release", (Function<Path, Path>) root -> Layout.releaseFile(root, "client", "../2.9.0"),
                        "invalid release id"),
                Arguments.of("publish root", (Function<Path, Path>) root -> Layout.tagRoot(root, "a/b"), "invalid tag"));
    }
}

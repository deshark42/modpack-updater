package io.github.deshark42.modpackupdater.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.UserError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathGuardTest {

    /** Junctions exercise Windows without requiring the privilege to create symbolic links. */
    private static void directoryLink(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").startsWith("Windows")) {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "junction creation timed out");
                assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8));
            } finally {
                process.destroyForcibly();
            }
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    @Test
    void permitsNewDirectoriesBelowAnExistingRoot(@TempDir Path root) {
        Path file = root.resolve("new/mods/a.jar");
        assertEquals(file, PathGuard.require(root, file));
    }

    @Test
    void permitsANewWorkspace(@TempDir Path root) {
        Path workspace = root.resolve("new-instance");
        Path file = workspace.resolve("mods/a.jar");
        assertEquals(file, PathGuard.require(workspace, file));
    }

    @Test
    void permitsARegularFileThatWillBecomeADirectory(@TempDir Path root) throws IOException {
        TestFiles.write(root, "mods", "old file");
        Path file = root.resolve("mods/a.jar");
        assertEquals(file, PathGuard.require(root, file));
    }

    @Test
    void rejectsPathsOutsideTheSelectedRoot(@TempDir Path root) throws IOException {
        Path workspace = Files.createDirectory(root.resolve("instance"));
        UserError error = assertThrows(UserError.class, () -> PathGuard.require(workspace, root.resolve("outside")));
        assertTrue(error.getMessage().contains("escapes its filesystem root"), error.getMessage());
    }

    @Test
    void rejectsDirectoryLinksToExternalFiles(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("instance"));
        Path outside = Files.createDirectory(root.resolve("outside folder"));
        TestFiles.write(outside, "a.jar", "external");
        directoryLink(workspace.resolve("mods"), outside);

        UserError error = assertThrows(UserError.class,
                () -> PathGuard.require(workspace, workspace.resolve("mods/a.jar")));

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        assertEquals("external", Files.readString(outside.resolve("a.jar")));
    }

    @Test
    void rejectsAliasesToInternalStateAsWell(@TempDir Path root) throws Exception {
        Path state = Files.createDirectory(root.resolve(".modpack-updater"));
        directoryLink(root.resolve("mods"), state);

        UserError error = assertThrows(UserError.class, () -> PathGuard.require(root, root.resolve("mods/new.jar")));

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        assertTrue(Files.isDirectory(state));
        try (var files = Files.list(state)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void permitsTheUserSelectedRootToBeALink(@TempDir Path root) throws Exception {
        Path real = Files.createDirectory(root.resolve("real-instance"));
        TestFiles.write(real, "mods/a.jar", "A");
        Path selected = root.resolve("selected-instance");
        directoryLink(selected, real);

        Path file = selected.resolve("mods/a.jar");
        assertEquals(selected, PathGuard.require(selected, selected));
        assertEquals(file, PathGuard.require(selected, file));
        assertEquals("A", Files.readString(file));
    }

    @Test
    void rejectsFileSymbolicLinksEvenWhenDangling(@TempDir Path root) throws IOException {
        Path workspace = Files.createDirectory(root.resolve("instance"));
        Path link = workspace.resolve("a.jar");
        try {
            Files.createSymbolicLink(link, root.resolve("missing.jar"));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "file symbolic links require filesystem support and Windows privileges: " + e);
        }

        UserError error = assertThrows(UserError.class, () -> PathGuard.require(workspace, link));

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        assertTrue(Files.isSymbolicLink(link));
    }
}

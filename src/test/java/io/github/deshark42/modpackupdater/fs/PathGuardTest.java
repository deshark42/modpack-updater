package io.github.deshark42.modpackupdater.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.UserError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        assertTrue(PathGuard.at(root).inspectAll(List.of(file)).isEmpty(),
                "the blocking file is not the requested terminal path");
    }

    @Test
    void rejectsPathsOutsideTheSelectedRoot(@TempDir Path root) throws IOException {
        Path workspace = Files.createDirectory(root.resolve("instance"));
        UserError error = assertThrows(UserError.class, () -> PathGuard.require(workspace, root.resolve("outside")));
        assertTrue(error.getMessage().contains("escapes its filesystem root"), error.getMessage());
        assertTrue(assertThrows(UserError.class,
                () -> PathGuard.at(workspace).inspectAll(List.of(root.resolve("outside"))))
                .getMessage().contains("escapes its filesystem root"));
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
        assertTrue(assertThrows(UserError.class,
                () -> PathGuard.at(workspace).inspectAll(List.of(workspace.resolve("mods/a.jar"))))
                .getMessage().contains("filesystem link or alias"));
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
        assertEquals(file, PathGuard.at(selected).require(file));
        Map<Path, BasicFileAttributes> checked = PathGuard.at(selected).inspectAll(List.of(selected, file));
        assertTrue(checked.get(selected).isDirectory(), "the trusted root link is followed");
        assertEquals(1, checked.get(file).size());
        assertEquals("A", Files.readString(file));
    }

    @Test
    void rechecksNewAndReplacedDescendantsOfACapturedRoot(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("instance"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        PathGuard guard = PathGuard.at(workspace);
        Path parent = workspace.resolve("mods");
        Path file = parent.resolve("nested/a.jar");
        assertEquals(file, guard.require(file));
        Files.createDirectories(file.getParent());
        Files.writeString(file, "ordinary file");
        assertEquals(file, guard.require(file));
        // Keep the checked tree, but replace its original name with a junction/symlink.
        Files.move(parent, workspace.resolve("old-mods"));
        directoryLink(parent, outside);

        UserError error = assertThrows(UserError.class, () -> guard.require(file));

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        try (var files = Files.list(outside)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void reusesOneBatchOfMetadataInLaterReadOnlyChecks(@TempDir Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("mods"));
        Path file = Files.writeString(directory.resolve("a.jar"), "ordinary file");
        PathGuard guard = PathGuard.at(root);
        PathGuard.Preflight preflight = PathGuard.preflight();

        assertTrue(guard.inspectAll(List.of(file), preflight).containsKey(file));
        assertTrue(guard.inspectAll(List.of(file, directory.resolve("b.jar")), preflight).containsKey(file));

        Path outside = Files.createDirectory(root.resolve("outside"));
        directoryLink(directory.resolve("linked.jar"), outside);
        // The first batch's findings cover only the paths it walked, so a link the second batch adds
        // is still checked afresh.
        assertTrue(assertThrows(UserError.class,
                () -> guard.inspectAll(List.of(directory.resolve("linked.jar")), preflight))
                .getMessage().contains("filesystem link or alias"));
    }

    @Test
    void capturedRootStillRejectsEscapesAndPermitsFileToDirectoryTransitions(@TempDir Path root)
            throws IOException {
        Path workspace = Files.createDirectory(root.resolve("instance"));
        PathGuard guard = PathGuard.at(workspace);
        Path file = workspace.resolve("mods/nested/a.jar");
        TestFiles.write(workspace, "mods", "old file");

        assertEquals(file, guard.require(file));
        assertThrows(UserError.class, () -> guard.require(root.resolve("outside")));
        Files.delete(workspace.resolve("mods"));
        Files.createDirectories(file.getParent());
        Files.writeString(file, "new file");
        assertEquals(file, guard.require(file));
    }

    @ParameterizedTest
    @ValueSource(strings = {"existing.jar", "missing/a.jar"})
    void rejectsNestedLinksEvenWhenTheirTargetsStayInsideTheRoot(String suffix, @TempDir Path root)
            throws Exception {
        Path state = Files.createDirectories(root.resolve(".modpack-updater/state"));
        Files.writeString(state.resolve("existing.jar"), "internal state");
        Path parent = Files.createDirectories(root.resolve("nested/ordinary"));
        directoryLink(parent.resolve("linked"), state);
        Path target = parent.resolve("linked").resolve(suffix);

        assertThrows(UserError.class, () -> PathGuard.require(root, target));
        assertThrows(UserError.class, () -> PathGuard.at(root).require(target));
        assertTrue(assertThrows(UserError.class, () -> PathGuard.at(root).inspectAll(List.of(target)))
                .getMessage().contains("filesystem link or alias"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"existing.jar", "missing/a.jar", "blocked.jar/child"})
    @EnabledOnOs(OS.WINDOWS)
    void rejectsNativeDirectoryAliasesBeforeExistingAndMissingSuffixes(String suffix, @TempDir Path root)
            throws IOException {
        Path directory = Files.createDirectory(root.resolve("mods"));
        Files.writeString(directory.resolve("existing.jar"), "ordinary file");
        Files.writeString(directory.resolve("blocked.jar"), "ordinary file blocker");
        // Win32 accepts a trailing dot as an alias for the directory, without a reparse point.
        Path target = root.resolve("mods.").resolve(suffix);

        assertThrows(UserError.class, () -> PathGuard.require(root, target));
        assertThrows(UserError.class, () -> PathGuard.at(root).require(target));
        assertTrue(assertThrows(UserError.class, () -> PathGuard.at(root).inspectAll(List.of(target)))
                .getMessage().contains("filesystem link or alias"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsShortDirectoryAliasesOfLinks(@TempDir Path root) throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path link = root.resolve("LongDirectoryName");
        directoryLink(link, outside);
        Path shortName = root.resolve("LONGDI~1");
        assumeTrue(Files.isDirectory(shortName) && Files.isSameFile(link, shortName),
                "8.3 short names must be enabled on this volume");

        Path target = shortName.resolve("a.jar");
        assertTrue(assertThrows(UserError.class, () -> PathGuard.require(root, target))
                .getMessage().contains("filesystem link or alias"));
        assertTrue(assertThrows(UserError.class, () -> PathGuard.at(root).inspectAll(List.of(target)))
                .getMessage().contains("filesystem link or alias"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void acceptsALeafFileRespelledInsideItsCheckedDirectory(@TempDir Path root) throws IOException {
        Path directory = Files.createDirectory(root.resolve("mods"));
        Files.writeString(directory.resolve("existing.jar"), "ordinary file");
        // Win32 also accepts a trailing dot as an alias for a file. The check resolves the deepest
        // existing directory, so this respelling cannot leave the directory it names; links and
        // junctions are still rejected by the no-follow attribute checks.
        Path target = directory.resolve("existing.jar.");

        assertEquals(target, PathGuard.require(root, target));
        assertTrue(PathGuard.at(root).inspectAll(List.of(target)).containsKey(target));
    }

    @Test
    void inspectsExistingAndMissingSiblingsWithoutReturningTheirParentAttributes(@TempDir Path root)
            throws IOException {
        TestFiles.write(root, "config/nested/a.cfg", "one");
        TestFiles.write(root, "config/nested/b.cfg", "another");
        TestFiles.write(root, "blocked", "ordinary file");
        Path first = root.resolve("config/nested/a.cfg");
        Path second = root.resolve("config/nested/b.cfg");
        Path missing = root.resolve("config/nested/new/c.cfg");
        Path blocked = root.resolve("blocked/child.cfg");

        Map<Path, BasicFileAttributes> checked = PathGuard.at(root)
                .inspectAll(List.of(root, first, second, missing, blocked));

        assertEquals(3, checked.size());
        assertTrue(checked.get(root).isDirectory());
        assertTrue(checked.get(first).isRegularFile());
        assertEquals(3, checked.get(first).size());
        assertEquals(7, checked.get(second).size());
        assertFalse(checked.containsKey(missing));
        assertFalse(checked.containsKey(blocked));
    }

    @ParameterizedTest(name = "previous parent exists: {0}")
    @ValueSource(booleans = {false, true})
    void rechecksDescendantsBetweenBulkInspections(boolean existing, @TempDir Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("instance"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path file = workspace.resolve("mods/a.jar");
        if (existing) {
            TestFiles.write(workspace, "mods/a.jar", "ordinary file");
        }
        PathGuard guard = PathGuard.at(workspace);
        assertEquals(existing, guard.inspectAll(List.of(file)).containsKey(file));
        if (existing) {
            Files.move(workspace.resolve("mods"), workspace.resolve("old-mods"));
        }
        directoryLink(workspace.resolve("mods"), outside);

        UserError error = assertThrows(UserError.class, () -> guard.inspectAll(List.of(file)));

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        try (var files = Files.list(outside)) {
            assertEquals(0, files.count());
        }
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
        assertTrue(assertThrows(UserError.class, () -> PathGuard.at(workspace).inspectAll(List.of(link)))
                .getMessage().contains("filesystem link or alias"));
    }
}

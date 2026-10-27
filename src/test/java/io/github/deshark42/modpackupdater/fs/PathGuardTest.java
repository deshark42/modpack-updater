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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class PathGuardTest {

    private enum EntryPoint {
        STATIC, CAPTURED, BULK;

        Object check(Path root, Path path) {
            return switch (this) {
                case STATIC -> PathGuard.require(root, path);
                case CAPTURED -> PathGuard.at(root).require(path);
                case BULK -> PathGuard.at(root).inspectAll(List.of(path));
            };
        }
    }

    /** Junctions exercise Windows without requiring symbolic-link privileges. */
    private static void directoryLink(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").startsWith("Windows")) {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            try (var output = process.getInputStream()) {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "junction creation timed out");
                assertEquals(0, process.exitValue(), new String(output.readAllBytes(), StandardCharsets.UTF_8));
            } finally {
                process.destroyForcibly();
            }
        } else {
            try {
                Files.createSymbolicLink(link, target);
            } catch (UnsupportedOperationException | IOException e) {
                assumeTrue(false, "directory symbolic links require filesystem support: " + e);
            }
        }
    }

    @Test
    void permitsNewDirectoriesBelowAnExistingRoot(@TempDir Path root) {
        Path file = root.resolve("new/mods/a.jar");
        assertEquals(file, PathGuard.require(root, file));
        assertFalse(Files.exists(file.getParent()));
    }

    @Test
    void permitsANewWorkspace(@TempDir Path root) {
        Path workspace = root.resolve("new-instance");
        Path file = workspace.resolve("mods/a.jar");
        assertEquals(file, PathGuard.require(workspace, file));
        assertFalse(Files.exists(workspace));
    }

    @ParameterizedTest(name = "{0} permits a file-to-directory transition")
    @EnumSource(EntryPoint.class)
    void permitsARegularFileThatWillBecomeADirectory(EntryPoint entry, @TempDir Path root) throws IOException {
        TestFiles.write(root, "mods", "old file");
        Path file = root.resolve("mods/a.jar");

        Object result = entry.check(root, file);

        assertEquals(entry == EntryPoint.BULK ? Map.of() : file, result);
        assertEquals("old file", Files.readString(root.resolve("mods")));
    }

    @ParameterizedTest(name = "{0} rejects an escape")
    @EnumSource(EntryPoint.class)
    void rejectsPathsOutsideTheSelectedRoot(EntryPoint entry, @TempDir Path root) throws IOException {
        Path workspace = Files.createDirectory(root.resolve("instance"));
        Path outside = root.resolve("outside");

        UserError error = assertThrows(UserError.class, () -> entry.check(workspace, outside));

        assertTrue(error.getMessage().contains("escapes its filesystem root"), error.getMessage());
        assertFalse(Files.exists(outside));
        assertEquals(Map.of(), Tree.list(workspace));
    }

    @ParameterizedTest(name = "{0} rejects an external directory link")
    @EnumSource(EntryPoint.class)
    void rejectsDirectoryLinksToExternalFiles(EntryPoint entry, @TempDir Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("instance"));
        Path outside = Files.createDirectory(root.resolve("outside folder"));
        TestFiles.write(outside, "a.jar", "external");
        directoryLink(workspace.resolve("mods"), outside);

        UserError error = assertThrows(UserError.class,
                () -> entry.check(workspace, workspace.resolve("mods/a.jar")));

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        assertEquals("external", Files.readString(outside.resolve("a.jar")));
        assertEquals(Map.of("a.jar", 8L), Tree.list(outside));
    }

    @Test
    void rejectsAliasesToInternalStateAsWell(@TempDir Path root) throws Exception {
        Path state = Files.createDirectory(root.resolve(".modpack-updater"));
        directoryLink(root.resolve("mods"), state);

        UserError error = assertThrows(UserError.class, () -> PathGuard.require(root, root.resolve("mods/new.jar")));

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        assertEquals(Map.of(), Tree.list(state));
    }

    @ParameterizedTest(name = "{0} permits a user-selected root link")
    @EnumSource(EntryPoint.class)
    void permitsTheUserSelectedRootToBeALink(EntryPoint entry, @TempDir Path root) throws Exception {
        Path real = Files.createDirectory(root.resolve("real-instance"));
        TestFiles.write(real, "mods/a.jar", "A");
        Path selected = root.resolve("selected-instance");
        directoryLink(selected, real);
        Path file = selected.resolve("mods/a.jar");

        Object result = entry.check(selected, file);

        if (entry == EntryPoint.BULK) {
            Map<?, ?> checked = (Map<?, ?>) result;
            assertEquals(java.util.Set.of(file), checked.keySet());
            assertEquals(1, ((BasicFileAttributes) checked.get(file)).size());
        } else {
            assertEquals(file, result);
        }
        assertEquals("A", Files.readString(file));
    }

    @Test
    void permitsTheSelectedRootLinkItself(@TempDir Path root) throws Exception {
        Path real = Files.createDirectory(root.resolve("real-instance"));
        Path selected = root.resolve("selected-instance");
        directoryLink(selected, real);

        Path checked = PathGuard.require(selected, selected);

        assertEquals(selected, checked);
    }

    @Test
    void inspectsTheUserSelectedRootLinkAsADirectory(@TempDir Path root) throws Exception {
        Path real = Files.createDirectory(root.resolve("real-instance"));
        Path selected = root.resolve("selected-instance");
        directoryLink(selected, real);

        Map<Path, BasicFileAttributes> checked = PathGuard.at(selected).inspectAll(List.of(selected));

        assertEquals(java.util.Set.of(selected), checked.keySet());
        assertTrue(checked.get(selected).isDirectory());
    }

    @Test
    void rechecksReplacedDescendantsOfACapturedRoot(@TempDir Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("instance"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        PathGuard guard = PathGuard.at(workspace);
        Path parent = workspace.resolve("mods");
        Path file = parent.resolve("nested/a.jar");
        TestFiles.write(workspace, "mods/nested/a.jar", "ordinary file");
        guard.require(file);
        Files.move(parent, workspace.resolve("old-mods"));
        directoryLink(parent, outside);

        UserError error = assertThrows(UserError.class, () -> guard.require(file));

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        assertEquals(Map.of(), Tree.list(outside));
        assertEquals("ordinary file", Files.readString(workspace.resolve("old-mods/nested/a.jar")));
    }

    @Test
    void checksNewPathsEvenWhenAnEarlierReadOnlyBatchSharesItsPreflight(@TempDir Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("mods"));
        Path file = Files.writeString(directory.resolve("a.jar"), "ordinary file");
        Path outside = Files.createDirectory(root.resolve("outside"));
        directoryLink(directory.resolve("linked.jar"), outside);
        PathGuard guard = PathGuard.at(root);
        PathGuard.Preflight preflight = PathGuard.preflight();
        guard.inspectAll(List.of(file), preflight);

        UserError error = assertThrows(UserError.class,
                () -> guard.inspectAll(List.of(directory.resolve("linked.jar")), preflight));

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        assertEquals("ordinary file", Files.readString(file));
        assertEquals(Map.of(), Tree.list(outside));
    }

    private static Stream<Arguments> nestedLinks() {
        return Stream.of(EntryPoint.values()).flatMap(entry -> Stream.of("existing.jar", "missing/a.jar")
                .map(suffix -> Arguments.of(entry, suffix)));
    }

    @ParameterizedTest(name = "{0} rejects internal link before {1}")
    @MethodSource("nestedLinks")
    void rejectsNestedLinksEvenWhenTheirTargetsStayInsideTheRoot(EntryPoint entry, String suffix,
            @TempDir Path root) throws Exception {
        Path state = Files.createDirectories(root.resolve(".modpack-updater/state"));
        Files.writeString(state.resolve("existing.jar"), "internal state");
        Path parent = Files.createDirectories(root.resolve("nested/ordinary"));
        directoryLink(parent.resolve("linked"), state);
        Path target = parent.resolve("linked").resolve(suffix);

        UserError error = assertThrows(UserError.class, () -> entry.check(root, target));

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        assertEquals(Map.of("existing.jar", 14L), Tree.list(state));
        assertEquals("internal state", Files.readString(state.resolve("existing.jar")));
    }

    private static Stream<Arguments> nativeAliases() {
        return Stream.of(EntryPoint.values()).flatMap(entry -> Stream.of("existing.jar", "missing/a.jar", "blocked.jar/child")
                .map(suffix -> Arguments.of(entry, suffix)));
    }

    @ParameterizedTest(name = "{0} rejects native alias before {1}")
    @MethodSource("nativeAliases")
    @EnabledOnOs(OS.WINDOWS)
    void rejectsNativeDirectoryAliasesBeforeExistingAndMissingSuffixes(EntryPoint entry, String suffix,
            @TempDir Path root) throws IOException {
        Path directory = Files.createDirectory(root.resolve("mods"));
        Files.writeString(directory.resolve("existing.jar"), "ordinary file");
        Files.writeString(directory.resolve("blocked.jar"), "ordinary file blocker");
        // Win32 accepts a trailing dot as an alias without a reparse point.
        Path target = root.resolve("mods.").resolve(suffix);

        UserError error = assertThrows(UserError.class, () -> entry.check(root, target));

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        assertEquals("ordinary file", Files.readString(directory.resolve("existing.jar")));
        assertEquals("ordinary file blocker", Files.readString(directory.resolve("blocked.jar")));
        assertEquals(Map.of("existing.jar", 13L, "blocked.jar", 21L), Tree.list(directory));
    }

    @ParameterizedTest(name = "{0} rejects a short directory alias")
    @EnumSource(EntryPoint.class)
    @EnabledOnOs(OS.WINDOWS)
    void rejectsShortDirectoryAliasesOfLinks(EntryPoint entry, @TempDir Path root) throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path link = root.resolve("LongDirectoryName");
        directoryLink(link, outside);
        Path shortName = root.resolve("LONGDI~1");
        assumeTrue(Files.isDirectory(shortName) && Files.isSameFile(link, shortName),
                "8.3 short names must be enabled on this volume");

        UserError error = assertThrows(UserError.class, () -> entry.check(root, shortName.resolve("a.jar")));

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        assertEquals(Map.of(), Tree.list(outside));
    }

    @ParameterizedTest(name = "{0} accepts a leaf file alias")
    @EnumSource(EntryPoint.class)
    @EnabledOnOs(OS.WINDOWS)
    void acceptsALeafFileRespelledInsideItsCheckedDirectory(EntryPoint entry, @TempDir Path root) throws IOException {
        Path directory = Files.createDirectory(root.resolve("mods"));
        Files.writeString(directory.resolve("existing.jar"), "ordinary file");
        Path target = directory.resolve("existing.jar.");

        Object result = entry.check(root, target);

        if (entry == EntryPoint.BULK) {
            Map<?, ?> checked = (Map<?, ?>) result;
            assertEquals(java.util.Set.of(target), checked.keySet());
            assertEquals(13, ((BasicFileAttributes) checked.get(target)).size());
        } else {
            assertEquals(target, result);
        }
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

        assertEquals(java.util.Set.of(root, first, second), checked.keySet());
        assertTrue(checked.get(root).isDirectory());
        assertTrue(checked.get(first).isRegularFile());
        assertEquals(3, checked.get(first).size());
        assertEquals(7, checked.get(second).size());
    }

    @Test
    void rechecksNewDescendantsBetweenBulkInspections(@TempDir Path root) throws Exception {
        rechecksDescendantsBetweenBulkInspections(false, root);
    }

    @Test
    void rechecksExistingDescendantsBetweenBulkInspections(@TempDir Path root) throws Exception {
        rechecksDescendantsBetweenBulkInspections(true, root);
    }

    private static void rechecksDescendantsBetweenBulkInspections(boolean existing, Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("instance"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path file = workspace.resolve("mods/a.jar");
        if (existing) {
            TestFiles.write(workspace, "mods/a.jar", "ordinary file");
        }
        PathGuard guard = PathGuard.at(workspace);
        guard.inspectAll(List.of(file));
        if (existing) {
            Files.move(workspace.resolve("mods"), workspace.resolve("old-mods"));
        }
        directoryLink(workspace.resolve("mods"), outside);

        UserError error = assertThrows(UserError.class, () -> guard.inspectAll(List.of(file)));

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        assertEquals(Map.of(), Tree.list(outside));
    }

    @ParameterizedTest(name = "{0} rejects a dangling file link")
    @EnumSource(EntryPoint.class)
    void rejectsFileSymbolicLinksEvenWhenDangling(EntryPoint entry, @TempDir Path root) throws IOException {
        Path workspace = Files.createDirectory(root.resolve("instance"));
        Path link = workspace.resolve("a.jar");
        try {
            Files.createSymbolicLink(link, root.resolve("missing.jar"));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "file symbolic links require filesystem support and Windows privileges: " + e);
        }

        UserError error = assertThrows(UserError.class, () -> entry.check(workspace, link));

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        assertTrue(Files.isSymbolicLink(link));
        assertFalse(Files.exists(root.resolve("missing.jar")));
    }
}

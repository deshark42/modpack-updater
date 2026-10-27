package io.github.deshark42.modpackupdater.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.ChunkTable;
import io.github.deshark42.modpackupdater.model.Chunking;
import io.github.deshark42.modpackupdater.model.FileEntry;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.Plan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalContentTest {
    private static final String TAG = "client";
    private static final String TARGET_PATH = "mods/new.jar";
    private static byte[] content() {
        return TestFiles.bytes("shared local payload");
    }

    private static ChunkTable table() {
        return new ChunkTable(Chunking.DEFAULT, content().length, List.of(Sha256.ofBytes(content())));
    }

    private static FileEntry entry() {
        return new FileEntry(Sha256.ofBytes(content()), content().length, table().indexId());
    }

    private static Manifest target() {
        return new Manifest(TAG, "2", Instant.EPOCH, Manifest.SHA256, Map.of(TARGET_PATH, entry()));
    }

    private static LocalContent.Reuse find(Path workspace, Plan plan) {
        return LocalContent.localChunks(workspace, Layout.backupDir(workspace, TAG, "1"), plan,
                List.of(TARGET_PATH), TAG, "2", target(), Map.of(table().indexId(), table()), 2);
    }

    @Test
    void matchingContentCanStayInPlaceWhenNoDeletionWillMoveIt(@TempDir Path workspace) throws IOException {
        TestFiles.write(workspace, TARGET_PATH, content());

        boolean matches = LocalContent.matchesTarget(workspace, TARGET_PATH, entry(), Set.of());

        assertTrue(matches);
        assertArrayEquals(content(), Files.readAllBytes(workspace.resolve(TARGET_PATH)));
    }

    @ParameterizedTest(name = "target content is not already present when the path is {0}")
    @ValueSource(strings = {"missing", "directory", "different size", "different hash"})
    void aNonmatchingPathNeedsAssembly(String state, @TempDir Path workspace) throws IOException {
        Path path = workspace.resolve(TARGET_PATH);
        byte[] different = content();
        different[0] ^= 1;
        switch (state) {
            case "directory" -> Files.createDirectories(path);
            case "different size" -> TestFiles.write(workspace, TARGET_PATH, "short");
            case "different hash" -> TestFiles.write(workspace, TARGET_PATH, different);
            default -> { }
        }

        boolean matches = LocalContent.matchesTarget(workspace, TARGET_PATH, entry(), Set.of());

        assertFalse(matches);
        switch (state) {
            case "missing" -> assertFalse(Files.exists(path));
            case "directory" -> assertTrue(Files.isDirectory(path));
            case "different size" -> assertEquals("short", Files.readString(path));
            case "different hash" -> assertArrayEquals(different, Files.readAllBytes(path));
            default -> throw new AssertionError(state);
        }
    }

    @Test
    void matchingContentNeedsAssemblyBeforeItsParentMovesToBackup(@TempDir Path workspace) throws IOException {
        TestFiles.write(workspace, TARGET_PATH, content());
        Set<Path> deletions = LocalContent.deletionPaths(workspace, List.of("mods"));

        boolean matches = LocalContent.matchesTarget(workspace, TARGET_PATH, entry(), deletions);

        assertFalse(matches);
        assertArrayEquals(content(), Files.readAllBytes(workspace.resolve(TARGET_PATH)));
    }

    @Test
    void anUnrelatedDeletionDoesNotForceMatchingContentToBeRewritten(@TempDir Path workspace) throws IOException {
        TestFiles.write(workspace, TARGET_PATH, content());
        TestFiles.write(workspace, "mods/old.jar", "unrelated");
        Set<Path> deletions = LocalContent.deletionPaths(workspace, List.of("mods/old.jar"));

        boolean matches = LocalContent.matchesTarget(workspace, TARGET_PATH, entry(), deletions);

        assertTrue(matches);
        assertEquals("unrelated", Files.readString(workspace.resolve("mods/old.jar")));
        assertArrayEquals(content(), Files.readAllBytes(workspace.resolve(TARGET_PATH)));
    }

    @Test
    void aBlockedDeletedDescendantDoesNotHideMatchingContentElsewhere(@TempDir Path workspace) throws IOException {
        TestFiles.write(workspace, "old", "player file blocks old descendant");
        TestFiles.write(workspace, TARGET_PATH, content());
        Set<Path> deletions = LocalContent.deletionPaths(workspace, List.of("old/deleted.jar"));

        boolean matches = LocalContent.matchesTarget(workspace, TARGET_PATH, entry(), deletions);

        assertTrue(matches);
        assertEquals("player file blocks old descendant", Files.readString(workspace.resolve("old")));
    }

    @Test
    void deletionSourcesIncludeOnlyExistingRealPaths(@TempDir Path workspace) throws IOException {
        TestFiles.write(workspace, "mods/old.jar", "old");
        TestFiles.write(workspace, "blocked", "player file");

        Set<Path> deletions = LocalContent.deletionPaths(workspace,
                List.of("mods/old.jar", "missing.jar", "blocked/old.jar"));

        assertEquals(Set.of(workspace.resolve("mods/old.jar").toRealPath()), deletions);
        assertEquals("old", Files.readString(workspace.resolve("mods/old.jar")));
        assertEquals("player file", Files.readString(workspace.resolve("blocked")));
        assertFalse(Files.exists(workspace.resolve("missing.jar")));
    }

    @ParameterizedTest(name = "a matching Windows alias {0} must be staged before the old spelling is deleted")
    @ValueSource(strings = {"mods/new.jar.", "mods/NEW.jar", "mods/new.jar.."})
    @EnabledOnOs(OS.WINDOWS) // These alternate spellings address the same native Windows directory entry.
    void aDeletedAliasCannotSupplyContentThatWouldRemainInPlace(String alias, @TempDir Path workspace)
            throws IOException {
        TestFiles.write(workspace, TARGET_PATH, content());
        assertTrue(Files.isSameFile(workspace.resolve(TARGET_PATH), workspace.resolve(alias)));
        Set<Path> deletions = LocalContent.deletionPaths(workspace, List.of(TARGET_PATH));

        boolean matches = LocalContent.matchesTarget(workspace, alias, entry(), deletions);

        assertFalse(matches);
        assertArrayEquals(content(), Files.readAllBytes(workspace.resolve(TARGET_PATH)));
    }

    @ParameterizedTest(name = "reusable chunks come from {0}")
    @ValueSource(strings = {"current target", "previous backup", "target backup", "deleted path"})
    void reusableContentCanComeFromEachKeptLocalSource(String source, @TempDir Path workspace) throws IOException {
        Path copy = switch (source) {
            case "current target" -> workspace.resolve(TARGET_PATH);
            case "previous backup" -> Layout.backupDir(workspace, TAG, "1").resolve(TARGET_PATH);
            case "target backup" -> Layout.backupDir(workspace, TAG, "2").resolve("different/name.jar");
            case "deleted path" -> workspace.resolve("mods/old.jar");
            default -> throw new AssertionError(source);
        };
        Files.createDirectories(copy.getParent());
        Files.write(copy, content());
        Plan plan = new Plan(List.of(TARGET_PATH), List.of(),
                source.equals("deleted path") ? List.of("mods/old.jar") : List.of(), content().length);

        LocalContent.Reuse reuse = find(workspace, plan);

        String hash = Sha256.ofBytes(content());
        assertEquals(Set.of(hash), reuse.chunks().keySet());
        assertEquals(1, reuse.files());
        assertEquals(copy, reuse.chunks().get(hash).file());
        assertArrayEquals(content(), Chunker.read(copy, reuse.chunks().get(hash).cut()));
        assertArrayEquals(content(), Files.readAllBytes(copy));
    }

    @Test
    void absentCopiesContributeNoReusableChunks(@TempDir Path workspace) throws IOException {
        Plan plan = new Plan(List.of(TARGET_PATH), List.of(), List.of("mods/deleted.jar"), content().length);

        LocalContent.Reuse reuse = find(workspace, plan);

        assertEquals(new LocalContent.Reuse(Map.of(), 0), reuse);
        try (var paths = Files.list(workspace)) {
            assertEquals(List.of(), paths.toList());
        }
    }

    @Test
    void anUnreadableCopyFallsBackToThePreviousBackup(@TempDir Path workspace) throws IOException {
        assumeTrue(workspace.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "the platform has no POSIX file permissions");
        TestFiles.write(workspace, TARGET_PATH, content());
        Path current = workspace.resolve(TARGET_PATH);
        Path backup = Layout.backupDir(workspace, TAG, "1").resolve(TARGET_PATH);
        Files.createDirectories(backup.getParent());
        Files.write(backup, content());
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(current);
        Files.setPosixFilePermissions(current, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(current), "this user can read files despite denied POSIX permissions");
            Plan plan = new Plan(List.of(), List.of(TARGET_PATH), List.of(), content().length);

            LocalContent.Reuse reuse = find(workspace, plan);

            assertEquals(Set.of(Sha256.ofBytes(content())), reuse.chunks().keySet());
            assertEquals(1, reuse.files());
            assertEquals(backup, reuse.chunks().get(Sha256.ofBytes(content())).file());
            assertArrayEquals(content(), Files.readAllBytes(backup));
        } finally {
            Files.setPosixFilePermissions(current, original);
        }
    }

    @Test
    void anUnreadableCopyWithoutABackupContributesNoChunks(@TempDir Path workspace) throws IOException {
        assumeTrue(workspace.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "the platform has no POSIX file permissions");
        TestFiles.write(workspace, TARGET_PATH, content());
        Path current = workspace.resolve(TARGET_PATH);
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(current);
        Files.setPosixFilePermissions(current, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(current), "this user can read files despite denied POSIX permissions");
            Plan plan = new Plan(List.of(), List.of(TARGET_PATH), List.of(), content().length);

            LocalContent.Reuse reuse = find(workspace, plan);

            assertEquals(new LocalContent.Reuse(Map.of(), 0), reuse);
            assertFalse(Files.exists(Layout.backupsDir(workspace, TAG)));
        } finally {
            Files.setPosixFilePermissions(current, original);
        }
    }

    @Test
    void aLinkInsideAKeptReleaseCannotRedirectChunkReuse(@TempDir Path root) throws IOException {
        Path workspace = root.resolve("workspace");
        Files.createDirectories(workspace);
        Path outside = root.resolve("outside");
        TestFiles.write(outside, "external.jar", content());
        Path kept = Layout.backupDir(workspace, TAG, "2");
        Files.createDirectories(kept);
        try {
            Files.createSymbolicLink(kept.resolve("mods"), outside);
        } catch (UnsupportedOperationException | IOException error) {
            assumeTrue(false, "symbolic links require filesystem support and Windows privileges: " + error);
        }
        Plan plan = new Plan(List.of(TARGET_PATH), List.of(), List.of(), content().length);

        UserError error = assertThrows(UserError.class, () -> find(workspace, plan));

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        assertArrayEquals(content(), Files.readAllBytes(outside.resolve("external.jar")));
        assertFalse(Files.exists(workspace.resolve(TARGET_PATH)));
        assertFalse(Files.exists(Layout.tempDir(workspace)));
    }
}

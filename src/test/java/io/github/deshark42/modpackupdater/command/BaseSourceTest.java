package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.deshark42.modpackupdater.TestArchives;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@ResourceLock(TestLogs.RESOURCE)
class BaseSourceTest {

    private static final String TAG = "client";

    @ParameterizedTest(name = "base={0}, base_pack={1} permits an empty release")
    @CsvSource({"true,false", "false,true", "true,true"})
    void eitherOrBothEmptyInputDirectoriesProduceAnEmptySource(boolean base, boolean packs, @TempDir Path workspace)
            throws IOException {
        if (base) {
            Files.createDirectories(Layout.baseDir(workspace, TAG));
        }
        if (packs) {
            Files.createDirectories(Layout.basePackDir(workspace, TAG));
        }

        try (BaseSource source = BaseSource.open(workspace, TAG)) {
            assertEquals(Map.of(), source.files());
        }

        assertFalse(Files.exists(Layout.tempDir(workspace)));
    }

    @Test
    void aSourceRequiresAtLeastOneInputDirectory(@TempDir Path workspace) throws IOException {
        Map<Path, byte[]> originals = inputBytes(workspace);
        UserError error = assertThrows(UserError.class, () -> BaseSource.open(workspace, TAG));

        assertInputsUnchanged(workspace, originals);

        assertTrue(error.getMessage().contains("at least one"), error.getMessage());
        assertTrue(error.getMessage().contains("base_pack"), error.getMessage());
        assertFalse(Files.exists(Layout.tempDir(workspace)));
    }

    @Test
    void anInvalidBaseRootFailsEvenWhenThePackDirectoryExists(@TempDir Path workspace) throws IOException {
        assertInvalidRoot(workspace, "base");
    }

    @Test
    void anInvalidPackRootFailsEvenWhenTheBaseDirectoryExists(@TempDir Path workspace) throws IOException {
        assertInvalidRoot(workspace, "base_pack");
    }

    private static void assertInvalidRoot(Path workspace, String name) throws IOException {
        Path tagRoot = Layout.tagRoot(workspace, TAG);
        TestFiles.write(tagRoot, name, "not a directory");
        Files.createDirectory(tagRoot.resolve(name.equals("base") ? "base_pack" : "base"));

        Map<Path, byte[]> originals = inputBytes(workspace);
        UserError error = assertThrows(UserError.class, () -> BaseSource.open(workspace, TAG));

        assertInputsUnchanged(workspace, originals);

        assertTrue(error.getMessage().contains("not a directory"), error.getMessage());
        assertTrue(error.getMessage().contains(tagRoot.resolve(name).toString()), error.getMessage());
        assertFalse(Files.exists(Layout.tempDir(workspace)));
    }

    @ParameterizedTest(name = "{0} is a link, dangling={1}")
    @CsvSource({"base,false", "base,true", "base_pack,false", "base_pack,true"})
    void aSourceRejectsLinkedInputRoots(String name, boolean dangling, @TempDir Path workspace) throws IOException {
        Path tagRoot = Files.createDirectories(Layout.tagRoot(workspace, TAG));
        Path target = workspace.resolve("target");
        if (!dangling) {
            Files.createDirectory(target);
        }
        Files.createDirectory(tagRoot.resolve(name.equals("base") ? "base_pack" : "base"));
        Path candidate = tagRoot.resolve(name);
        link(candidate, target);

        Map<Path, byte[]> originals = inputBytes(workspace);
        UserError error = assertThrows(UserError.class, () -> BaseSource.open(workspace, TAG));

        assertInputsUnchanged(workspace, originals);

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        assertTrue(Files.isSymbolicLink(candidate));
        assertFalse(Files.exists(Layout.tempDir(workspace)));
    }

    @ParameterizedTest(name = "nested {0}, directory={1}, dangling={2}")
    @CsvSource({
            "base,false,false", "base,true,false", "base,false,true",
            "base_pack,false,false", "base_pack,true,false", "base_pack,false,true"
    })
    void aSourceRejectsLinksInsideEitherInput(String name, boolean directory, boolean dangling,
            @TempDir Path workspace) throws IOException {
        Path root = Files.createDirectories(Layout.tagRoot(workspace, TAG).resolve(name));
        Path target = workspace.resolve("target");
        if (!dangling) {
            if (directory) {
                Files.createDirectory(target);
            } else {
                Files.writeString(target, "target");
            }
        }
        Path candidate = root.resolve("nested/linked.zip");
        Files.createDirectories(candidate.getParent());
        link(candidate, target);

        Map<Path, byte[]> originals = inputBytes(workspace);
        UserError error = assertThrows(UserError.class, () -> BaseSource.open(workspace, TAG));

        assertInputsUnchanged(workspace, originals);

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        assertTrue(Files.isSymbolicLink(candidate));
        assertFalse(Files.exists(Layout.tempDir(workspace)));
    }

    @ParameterizedTest(name = "legacy root archive {0} requires migration")
    @ValueSource(strings = {"base.zip", "base.7z", "base.ZIP", "base.7Z"})
    void aLegacyRootArchiveRequiresMigrationToBasePack(String name, @TempDir Path workspace) throws IOException {
        Files.createDirectories(Layout.baseDir(workspace, TAG));
        TestFiles.write(Layout.tagRoot(workspace, TAG), name, "legacy archive");

        Map<Path, byte[]> originals = inputBytes(workspace);
        UserError error = assertThrows(UserError.class, () -> BaseSource.open(workspace, TAG));

        assertInputsUnchanged(workspace, originals);

        assertTrue(error.getMessage().contains(name), error.getMessage());
        assertTrue(error.getMessage().contains("move it into"), error.getMessage());
        assertTrue(error.getMessage().contains("base_pack"), error.getMessage());
        assertFalse(Files.exists(Layout.tempDir(workspace)));
    }

    @ParameterizedTest(name = "non-archive {0} fails before extraction")
    @ValueSource(strings = {"readme.txt", "nested/mod.jar", "nested/pack.zip.txt"})
    void aNonArchiveFailsBeforeAnyPackageIsExtracted(String name, @TempDir Path workspace) throws IOException {
        archive(workspace, "a.zip", Map.of("valid.txt", "valid"));
        TestFiles.write(Layout.basePackDir(workspace, TAG), name, "not a package");

        Map<Path, byte[]> originals = inputBytes(workspace);
        UserError error = assertThrows(UserError.class, () -> BaseSource.open(workspace, TAG));

        assertInputsUnchanged(workspace, originals);

        assertTrue(error.getMessage().contains("ZIP or 7z"), error.getMessage());
        assertTrue(error.getMessage().contains(name.substring(name.lastIndexOf('/') + 1)), error.getMessage());
        assertFalse(Files.exists(Layout.tempDir(workspace)), "all package filenames are validated before extraction");
    }

    @Test
    void aSourceMapsNamedArchivesToTheirRelativeParentDirectories(@TempDir Path workspace)
            throws IOException {
        Path base = Layout.baseDir(workspace, TAG);
        TestFiles.write(base, "raw.txt", "raw file");
        Files.createDirectories(base.resolve("one_path/second_path/empty"));
        archive(workspace, "one_path/test.ZIP", Map.of(
                "root.txt", "root", "second_path/second.txt", "second", "nested.zip", "not recursively unpacked"));
        archive(workspace, "one_path/another.7Z", Map.of("second_path/third.txt", "third"));
        archive(workspace, "different-name.zip", Map.of("at-root.txt", "top"));
        List<Path> extracted = new ArrayList<>();

        Map<Path, byte[]> originals = inputBytes(workspace);

        try (BaseSource source = BaseSource.open(workspace, TAG)) {
            assertEquals(Map.of(
                    "raw.txt", 8L,
                    "one_path/root.txt", 4L,
                    "one_path/second_path/second.txt", 6L,
                    "one_path/second_path/third.txt", 5L,
                    "one_path/nested.zip", 24L,
                    "at-root.txt", 3L), source.files());
            assertEquals(base.resolve("raw.txt"), source.file("raw.txt"));
            assertEquals("root", Files.readString(source.file("one_path/root.txt")));
            assertEquals("second", Files.readString(source.file("one_path/second_path/second.txt")));
            assertEquals("third", Files.readString(source.file("one_path/second_path/third.txt")));
            assertEquals("not recursively unpacked", Files.readString(source.file("one_path/nested.zip")));
            assertEquals("top", Files.readString(source.file("at-root.txt")));
            assertEquals(List.of(base, Layout.basePackDir(workspace, TAG)), source.inputs());
            for (String path : source.files().keySet()) {
                if (!path.equals("raw.txt")) {
                    extracted.add(source.file(path));
                }
            }
        }

        assertTrue(extracted.stream().noneMatch(Files::exists));
        assertInputsUnchanged(workspace, originals);
        assertEquals("raw file", Files.readString(base.resolve("raw.txt")));
        assertNoStages(workspace);
    }

    @Test
    void aSourceLeavesRawBaseFilesInPlaceWithoutCopyingThem(@TempDir Path workspace) throws IOException {
        Path base = Layout.baseDir(workspace, TAG);
        TestFiles.write(base, "mods/a.jar", "original");
        Files.createDirectories(Layout.basePackDir(workspace, TAG));

        try (BaseSource source = BaseSource.open(workspace, TAG)) {
            assertEquals(List.of(base), source.inputs(), "empty base_pack preserves the base-only scan label");
            assertEquals(base.resolve("mods/a.jar"), source.file("mods/a.jar"));
            Files.writeString(base.resolve("mods/a.jar"), "changed");
            assertEquals("changed", Files.readString(source.file("mods/a.jar")));
        }

        assertEquals("changed", Files.readString(base.resolve("mods/a.jar")));
        assertFalse(Files.exists(Layout.tempDir(workspace)));
    }

    @Test
    void aSourceRejectsAFileThatBecomesALinkAfterTheScan(@TempDir Path workspace) throws IOException {
        Path base = Layout.baseDir(workspace, TAG);
        TestFiles.write(base, "mods/a.jar", "original");
        Path outside = workspace.resolve("outside.jar");
        Files.writeString(outside, "outside");
        try (BaseSource source = BaseSource.open(workspace, TAG)) {
            Files.delete(base.resolve("mods/a.jar"));
            link(base.resolve("mods/a.jar"), outside);

            UserError error = assertThrows(UserError.class, () -> source.file("mods/a.jar"));

            assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        }
        assertEquals("outside", Files.readString(outside));
    }

    @ParameterizedTest(name = "raw {0} collides with packed {1}")
    @CsvSource({"file,file", "file,child", "child,file", "directory,file", "file,directory"})
    void aSourceRejectsRawAndPackedFileOrDirectoryCollisions(String raw, String packed, @TempDir Path workspace)
            throws IOException {
        rawEntry(workspace, raw);
        archive(workspace, "test.zip", entry(packed));

        assertCollision(workspace);
    }

    @Test
    void aSourceRejectsDuplicateFilesEvenWhenTheirBytesAreIdentical(@TempDir Path workspace) throws IOException {
        TestFiles.write(Layout.baseDir(workspace, TAG), "collision", "identical");
        archive(workspace, "test.zip", Map.of("collision", "identical"));

        assertCollision(workspace);
    }

    @ParameterizedTest(name = "packed {0} collides with packed {1}")
    @CsvSource({"file,file", "file,child", "child,file", "directory,file", "file,directory"})
    void aCollisionBetweenPackagesReleasesBothStages(String first, String second,
            @TempDir Path workspace) throws IOException {
        archive(workspace, "a.zip", entry(first));
        archive(workspace, "b.7z", entry(second));

        assertCollision(workspace);
    }

    @ParameterizedTest(name = "raw first={0}, native aliases {1} and {2}")
    @CsvSource({
            "true, mods/A.jar, mods/a.jar, mods/A.jar, mods/a.jar",
            "false, mods/A.jar, mods/a.jar, mods/A.jar, mods/a.jar",
            "true, Mods/a.jar, mods/a.jar, Mods/a.jar, mods/a.jar",
            "false, Mods/a.jar, mods/a.jar, Mods/a.jar, mods/a.jar",
            "true, Mods, mods/a.jar, Mods, mods",
            "false, Mods, mods/a.jar, Mods, mods",
            "true, Mods/a.jar, mods, Mods, mods",
            "false, Mods/a.jar, mods, Mods, mods",
            "true, Mods/, mods, Mods, mods",
            "false, Mods/, mods, Mods, mods",
            "true, Mods, mods/, Mods, mods",
            "false, Mods, mods/, Mods, mods"
    })
    void aSourceUsesNativePathIdentityWithoutChangingLogicalSpelling(boolean rawFirst, String first, String second,
            String firstIdentity, String secondIdentity, @TempDir Path workspace) throws IOException {
        Path firstInput;
        if (rawFirst) {
            firstInput = Layout.baseDir(workspace, TAG).resolve(first);
            if (first.endsWith("/")) {
                Files.createDirectories(firstInput);
            } else {
                TestFiles.write(Layout.baseDir(workspace, TAG), first, "first");
            }
        } else {
            firstInput = archive(workspace, "a.zip", Map.of(first, first.endsWith("/") ? "" : "first"));
        }
        Path secondInput = archive(workspace, "b.7z", Map.of(second, second.endsWith("/") ? "" : "second"));
        byte[] firstBytes = rawFirst ? null : Files.readAllBytes(firstInput);
        byte[] secondBytes = Files.readAllBytes(secondInput);
        boolean aliases = workspace.getFileSystem().getPath(firstIdentity)
                .equals(workspace.getFileSystem().getPath(secondIdentity));

        if (aliases) {
            Map<Path, byte[]> originals = inputBytes(workspace);
            UserError error = assertThrows(UserError.class, () -> BaseSource.open(workspace, TAG));

            assertInputsUnchanged(workspace, originals);
            assertTrue(error.getMessage().contains("base input collision"), error.getMessage());
            assertTrue(error.getMessage().contains("conflicts with"), error.getMessage());
        } else {
            Map<String, Long> expected = new TreeMap<>();
            if (!first.endsWith("/")) {
                expected.put(first, 5L);
            }
            if (!second.endsWith("/")) {
                expected.put(second, 6L);
            }
            try (BaseSource source = BaseSource.open(workspace, TAG)) {
                assertEquals(expected, source.files(), "logical spelling remains distinct on this filesystem");
                if (!first.endsWith("/")) {
                    assertEquals("first", Files.readString(source.file(first)));
                }
                if (!second.endsWith("/")) {
                    assertEquals("second", Files.readString(source.file(second)));
                }
            }
        }

        if (rawFirst) {
            if (first.endsWith("/")) {
                assertTrue(Files.isDirectory(firstInput));
            } else {
                assertEquals("first", Files.readString(firstInput));
            }
        } else {
            assertArrayEquals(firstBytes, Files.readAllBytes(firstInput));
        }
        assertArrayEquals(secondBytes, Files.readAllBytes(secondInput));
        assertNoStages(workspace);
    }

    @Test
    void rawAndPackedNativeEquivalentDirectoriesMergeTheirDistinctFiles(@TempDir Path workspace) throws IOException {
        assertEquivalentDirectories(workspace, true);
    }

    @Test
    void twoPackedNativeEquivalentDirectoriesMergeTheirDistinctFiles(@TempDir Path workspace) throws IOException {
        assertEquivalentDirectories(workspace, false);
    }

    private static void assertEquivalentDirectories(Path workspace, boolean rawFirst) throws IOException {
        if (rawFirst) {
            Files.createDirectories(Layout.baseDir(workspace, TAG).resolve("Mods/empty"));
            TestFiles.write(Layout.baseDir(workspace, TAG), "Mods/first.jar", "first");
        } else {
            archive(workspace, "a.zip", Map.of("Mods/empty/", "", "Mods/first.jar", "first"));
        }
        archive(workspace, "b.7z", Map.of("mods/empty/", "", "mods/second.jar", "second"));

        try (BaseSource source = BaseSource.open(workspace, TAG)) {
            assertEquals(Map.of("Mods/first.jar", 5L, "mods/second.jar", 6L), source.files());
            assertEquals("first", Files.readString(source.file("Mods/first.jar")));
            assertEquals("second", Files.readString(source.file("mods/second.jar")));
        }

        assertNoStages(workspace);
    }

    @Test
    void aRawFileUsesNativeIdentityForMappedParentConflicts(@TempDir Path workspace) throws IOException {
        assertMappedParent(workspace, true);
    }

    @Test
    void aPackedFileUsesNativeIdentityForMappedParentConflicts(@TempDir Path workspace) throws IOException {
        assertMappedParent(workspace, false);
    }

    private static void assertMappedParent(Path workspace, boolean rawFirst) throws IOException {
        if (rawFirst) {
            TestFiles.write(Layout.baseDir(workspace, TAG), "Mods", "first");
        } else {
            archive(workspace, "a.zip", Map.of("Mods", "first"));
        }
        archive(workspace, "mods/nested/b.7z", Map.of("second.jar", "second"));
        boolean aliases = workspace.getFileSystem().getPath("Mods")
                .equals(workspace.getFileSystem().getPath("mods"));

        if (aliases) {
            Map<Path, byte[]> originals = inputBytes(workspace);
            UserError error = assertThrows(UserError.class, () -> BaseSource.open(workspace, TAG));

            assertInputsUnchanged(workspace, originals);
            assertTrue(error.getMessage().contains("base input collision"), error.getMessage());
            assertTrue(error.getMessage().contains("conflicts with"), error.getMessage());
        } else {
            try (BaseSource source = BaseSource.open(workspace, TAG)) {
                assertEquals(Map.of("Mods", 5L, "mods/nested/second.jar", 6L), source.files());
                assertEquals("first", Files.readString(source.file("Mods")));
                assertEquals("second", Files.readString(source.file("mods/nested/second.jar")));
            }
        }

        assertNoStages(workspace);
    }

    @Test
    void aRawParentConflictsWithAPackedChildBeyondAdjacentSortedPaths(@TempDir Path workspace) throws IOException {
        assertNonAdjacentCollision(workspace, true);
    }

    @Test
    void aPackedParentConflictsWithARawChildBeyondAdjacentSortedPaths(@TempDir Path workspace) throws IOException {
        assertNonAdjacentCollision(workspace, false);
    }

    private static void assertNonAdjacentCollision(Path workspace, boolean fileFirst) throws IOException {
        TestFiles.write(Layout.baseDir(workspace, TAG), "collision.txt", "sibling");
        if (fileFirst) {
            TestFiles.write(Layout.baseDir(workspace, TAG), "collision", "parent");
            archive(workspace, "test.zip", Map.of("collision/child.txt", "child"));
        } else {
            TestFiles.write(Layout.baseDir(workspace, TAG), "collision/child.txt", "child");
            archive(workspace, "test.zip", Map.of("collision", "parent"));
        }

        assertCollision(workspace);
    }

    @Test
    void matchingDirectoriesMergeIncludingExplicitEmptyDirectories(@TempDir Path workspace) throws IOException {
        Files.createDirectories(Layout.baseDir(workspace, TAG).resolve("shared/empty"));
        TestFiles.write(Layout.baseDir(workspace, TAG), "shared/raw.txt", "raw");
        archive(workspace, "a.zip", Map.of("shared/empty/", "", "shared/a.txt", "a"));
        archive(workspace, "b.7z", Map.of("shared/empty/", "", "shared/b.txt", "b"));

        try (BaseSource source = BaseSource.open(workspace, TAG)) {
            assertEquals(Map.of("shared/raw.txt", 3L, "shared/a.txt", 1L, "shared/b.txt", 1L), source.files());
        }

        assertNoStages(workspace);
    }

    @Test
    void emptyContainerDirectoriesAndPackagesDoNotCreateCollisions(@TempDir Path workspace) throws IOException {
        TestFiles.write(Layout.baseDir(workspace, TAG), "collision", "raw");
        Files.createDirectories(Layout.basePackDir(workspace, TAG).resolve("collision/empty-container"));
        archive(workspace, "collision/empty.zip", Map.of());
        archive(workspace, "collision/empty.7z", Map.of());

        try (BaseSource source = BaseSource.open(workspace, TAG)) {
            assertEquals(Map.of("collision", 3L), source.files());
            assertEquals("raw", Files.readString(source.file("collision")));
        }

        assertNoStages(workspace);
    }

    @Test
    void aPackedFileRegistersItsMappedParentForCollisionDetection(@TempDir Path workspace) throws IOException {
        assertRequiredParent(workspace, "file");
    }

    @Test
    void aPackedDirectoryRegistersItsMappedParentForCollisionDetection(@TempDir Path workspace) throws IOException {
        assertRequiredParent(workspace, "directory");
    }

    private static void assertRequiredParent(Path workspace, String kind) throws IOException {
        TestFiles.write(Layout.baseDir(workspace, TAG), "collision", "raw");
        archive(workspace, "collision/deeper/test.zip", kind.equals("file")
                ? Map.of("entry.txt", "content") : Map.of("empty/", ""));

        assertCollision(workspace);
    }

    @Test
    void aMalformedArchiveReleasesEveryEarlierPackage(@TempDir Path workspace) throws IOException {
        archive(workspace, "a.zip", Map.of("first.txt", "first"));
        archive(workspace, "b.7z", Map.of("second.txt", "second"));
        TestFiles.write(Layout.basePackDir(workspace, TAG), "z.zip", "malformed");

        Map<Path, byte[]> originals = inputBytes(workspace);
        UserError error = assertThrows(UserError.class, () -> BaseSource.open(workspace, TAG));

        assertInputsUnchanged(workspace, originals);

        assertTrue(error.getMessage().contains("z.zip"), error.getMessage());
        assertNoStages(workspace);
    }

    @Test
    void anUnsafeArchiveReleasesEveryEarlierPackage(@TempDir Path workspace) throws IOException {
        archive(workspace, "a.zip", Map.of("first.txt", "first"));
        archive(workspace, "z.zip", Map.of("../escape.txt", "unsafe"));

        Map<Path, byte[]> originals = inputBytes(workspace);
        UserError error = assertThrows(UserError.class, () -> BaseSource.open(workspace, TAG));

        assertInputsUnchanged(workspace, originals);

        assertTrue(error.getMessage().contains("unsafe entry path"), error.getMessage());
        assertFalse(Files.exists(Layout.tempDir(workspace).resolve("escape.txt")));
        assertNoStages(workspace);
    }

    @Test
    void aFailingCallerStillReleasesEveryArchiveStage(@TempDir Path workspace) throws IOException {
        archive(workspace, "a.zip", Map.of("first.txt", "first"));
        archive(workspace, "b.7z", Map.of("second.txt", "second"));
        Map<Path, byte[]> originals = inputBytes(workspace);

        IOException error = assertThrows(IOException.class, () -> {
            try (BaseSource source = BaseSource.open(workspace, TAG)) {
                assertEquals("first", Files.readString(source.file("first.txt")));
                assertEquals("second", Files.readString(source.file("second.txt")));
                throw new IOException("publication failed");
            }
        });

        assertEquals("publication failed", error.getMessage());
        assertInputsUnchanged(workspace, originals);
        assertNoStages(workspace);
    }

    private static void assertCollision(Path workspace) throws IOException {
        Map<Path, byte[]> originals = inputBytes(workspace);
        UserError error = assertThrows(UserError.class, () -> BaseSource.open(workspace, TAG));

        assertInputsUnchanged(workspace, originals);
        assertTrue(error.getMessage().contains("base input collision at collision:"), error.getMessage());
        assertTrue(error.getMessage().contains("conflicts with"), error.getMessage());
        assertNoStages(workspace);
    }

    private static Map<Path, byte[]> inputBytes(Path workspace) throws IOException {
        Path root = Layout.tagRoot(workspace, TAG);
        if (!Files.exists(root)) {
            return Map.of();
        }
        Map<Path, byte[]> inputs = new TreeMap<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                inputs.put(root.relativize(file), Files.readAllBytes(file));
            }
        }
        return inputs;
    }

    private static void assertInputsUnchanged(Path workspace, Map<Path, byte[]> originals) throws IOException {
        Map<Path, byte[]> after = inputBytes(workspace);
        assertEquals(originals.keySet(), after.keySet(), "source input paths remain unchanged");
        for (Map.Entry<Path, byte[]> input : originals.entrySet()) {
            assertArrayEquals(input.getValue(), after.get(input.getKey()), input.getKey().toString());
        }
    }

    private static void rawEntry(Path workspace, String kind) throws IOException {
        if (kind.equals("directory")) {
            Files.createDirectories(Layout.baseDir(workspace, TAG).resolve("collision"));
        } else {
            TestFiles.write(Layout.baseDir(workspace, TAG), kind.equals("file") ? "collision" : "collision/child.txt",
                    "raw");
        }
    }

    private static Map<String, String> entry(String kind) {
        return switch (kind) {
            case "file" -> Map.of("collision", "packed");
            case "child" -> Map.of("collision/child.txt", "packed");
            case "directory" -> Map.of("collision/", "");
            default -> throw new IllegalArgumentException("unknown entry kind: " + kind);
        };
    }

    private static Path archive(Path workspace, String name, Map<String, String> entries) throws IOException {
        Map<String, byte[]> bytes = new TreeMap<>();
        entries.forEach((path, content) -> bytes.put(path, TestFiles.bytes(content)));
        return TestArchives.write(Layout.basePackDir(workspace, TAG).resolve(name), bytes);
    }

    private static void link(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links require filesystem support and Windows privileges: " + e);
        }
    }

    private static List<Path> stages(Path workspace) throws IOException {
        Path temp = Layout.tempDir(workspace);
        if (!Files.exists(temp)) {
            return List.of();
        }
        try (var entries = Files.list(temp)) {
            return entries.toList();
        }
    }

    private static void assertNoStages(Path workspace) throws IOException {
        assertEquals(List.of(), stages(workspace));
    }
}

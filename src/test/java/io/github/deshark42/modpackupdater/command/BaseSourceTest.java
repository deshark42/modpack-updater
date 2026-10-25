package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BaseSourceTest {

    private static final String TAG = "client";

    @ParameterizedTest(name = "base={0}, zip={1}, 7z={2} selects {3}")
    @CsvSource({
            "empty, false, false, base",
            "files, false, false, base",
            "directories, false, false, base",
            "missing, true, false, base.zip",
            "missing, false, true, base.7z",
            "empty, true, false, base.zip",
            "empty, false, true, base.7z"
    })
    void selectsTheOnlyUsableBase(String base, boolean zip, boolean sevenZip, String selected,
            @TempDir Path workspace) throws IOException {
        inputs(workspace, base, zip, sevenZip);

        Path input = BaseSource.select(workspace, TAG);

        assertEquals(Layout.tagRoot(workspace, TAG).resolve(selected), input);
        assertFalse(Files.exists(Layout.tempDir(workspace)), "selection does not extract an archive");
    }

    @ParameterizedTest(name = "base={0}, zip={1}, 7z={2} is ambiguous")
    @CsvSource({
            "missing, true, true",
            "empty, true, true",
            "files, true, true",
            "files, true, false",
            "files, false, true",
            "directories, true, false",
            "directories, false, true"
    })
    void rejectsMultipleBaseInputs(String base, boolean zip, boolean sevenZip,
            @TempDir Path workspace) throws IOException {
        inputs(workspace, base, zip, sevenZip);

        UserError error = assertThrows(UserError.class, () -> BaseSource.select(workspace, TAG));

        assertTrue(error.getMessage().contains("ambiguous base input"), error.getMessage());
        assertFalse(Files.exists(Layout.tempDir(workspace)), "ambiguity fails before extraction");
    }

    @Test
    void reportsAMissingBaseAsBefore(@TempDir Path workspace) {
        UserError error = assertThrows(UserError.class, () -> BaseSource.select(workspace, TAG));

        assertTrue(error.getMessage().contains("no such directory"), error.getMessage());
        assertFalse(Files.exists(Layout.tempDir(workspace)));
    }

    @ParameterizedTest(name = "{0} has the wrong file type")
    @ValueSource(strings = {"base", "base.zip", "base.7z"})
    void rejectsAnInvalidCandidateEvenWhenAnotherInputExists(String name, @TempDir Path workspace)
            throws IOException {
        Path candidate = Layout.tagRoot(workspace, TAG).resolve(name);
        if (name.equals(Layout.BASE)) {
            TestFiles.write(candidate.getParent(), name, "not a directory");
            TestFiles.write(candidate.getParent(), "base.zip", "archive placeholder");
        } else {
            TestFiles.write(Layout.baseDir(workspace, TAG), "mods/a.jar", "directory base");
            Files.createDirectory(candidate);
        }

        UserError error = assertThrows(UserError.class, () -> BaseSource.select(workspace, TAG));

        String reason = name.equals(Layout.BASE) ? "not a directory" : "not a regular file";
        assertTrue(error.getMessage().contains(reason), error.getMessage());
        assertTrue(error.getMessage().contains(candidate.toString()), error.getMessage());
        assertFalse(Files.exists(Layout.tempDir(workspace)));
    }

    @ParameterizedTest(name = "{0} is a link, dangling={1}")
    @CsvSource({
            "base, false", "base, true",
            "base.zip, false", "base.zip, true",
            "base.7z, false", "base.7z, true"
    })
    void rejectsLinkedCandidatesInsteadOfIgnoringThem(String name, boolean dangling,
            @TempDir Path workspace) throws IOException {
        Path tagRoot = Files.createDirectories(Layout.tagRoot(workspace, TAG));
        Path target = workspace.resolve("target");
        if (!dangling) {
            if (name.equals(Layout.BASE)) {
                Files.createDirectory(target);
            } else {
                TestFiles.write(workspace, "target", "archive placeholder");
            }
        }
        Path candidate = tagRoot.resolve(name);
        if (name.equals(Layout.BASE)) {
            TestFiles.write(tagRoot, "base.zip", "archive placeholder");
        } else {
            TestFiles.write(Layout.baseDir(workspace, TAG), "mods/a.jar", "directory base");
        }
        try {
            Files.createSymbolicLink(candidate, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links require filesystem support and Windows privileges: " + e);
        }

        UserError error = assertThrows(UserError.class, () -> BaseSource.select(workspace, TAG));

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        assertTrue(Files.isSymbolicLink(candidate));
        assertFalse(Files.exists(Layout.tempDir(workspace)));
    }

    @ParameterizedTest(name = "{0} is ignored because of its case")
    @ValueSource(strings = {"base.ZIP", "base.7Z"})
    void rejectsACaseVariantArchiveInsteadOfPublishingTheEmptyBase(String name, @TempDir Path workspace)
            throws IOException {
        Files.createDirectories(Layout.baseDir(workspace, TAG));
        TestFiles.write(Layout.tagRoot(workspace, TAG), name, "archive placeholder");

        UserError error = assertThrows(UserError.class, () -> BaseSource.select(workspace, TAG));

        assertTrue(error.getMessage().contains(name), error.getMessage());
        assertTrue(error.getMessage().contains("case-sensitive"), error.getMessage());
        assertFalse(Files.exists(Layout.tempDir(workspace)));
    }

    @Test
    void leavesADirectoryBaseInPlaceWhenClosed(@TempDir Path workspace) throws IOException {
        TestFiles.write(Layout.baseDir(workspace, TAG), "mods/a.jar", "directory base");

        try (BaseSource source = BaseSource.open(workspace, TAG)) {
            assertEquals(Layout.baseDir(workspace, TAG), source.input());
            assertEquals(source.input(), source.root());
        }

        assertEquals("directory base", Files.readString(Layout.baseDir(workspace, TAG).resolve("mods/a.jar")));
        assertFalse(Files.exists(Layout.tempDir(workspace)));
    }

    private static void inputs(Path workspace, String base, boolean zip, boolean sevenZip) throws IOException {
        Path directory = Layout.baseDir(workspace, TAG);
        switch (base) {
            case "missing" -> { }
            case "empty" -> Files.createDirectories(directory);
            case "files" -> TestFiles.write(directory, "mods/a.jar", "directory base");
            case "directories" -> Files.createDirectories(directory.resolve("mods"));
            default -> throw new IllegalArgumentException("unknown base fixture: " + base);
        }
        if (zip) {
            TestFiles.write(Layout.tagRoot(workspace, TAG), "base.zip", "archive placeholder");
        }
        if (sevenZip) {
            TestFiles.write(Layout.tagRoot(workspace, TAG), "base.7z", "archive placeholder");
        }
    }
}

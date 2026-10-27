package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SafePathTest {

    @ParameterizedTest(name = "{0} normalizes to {1}")
    @CsvSource({
            "mods/x.jar, mods/x.jar",
            "mods/./x.jar, mods/x.jar",
            "a/b/, a/b",
            "a//b, a/b",
            "config/.modpack-updater/file, config/.modpack-updater/file",
            ".modpack-updater-extra/file, .modpack-updater-extra/file"
    })
    void plainRelativePathsNormalizeInsideTheWorkspace(String input, String expected) {
        assertEquals(expected, SafePath.require(input));
    }

    @ParameterizedTest(name = "\"{0}\" is reserved for the updater")
    @ValueSource(strings = {".modpack-updater", ".modpack-updater/config.json",
            ".modpack-updater/client/current.json", ".modpack-updater/client/releases/old.json",
            ".modpack-updater/client/backup/old/mods/a.jar", ".modpack-updater/tmp/file",
            ".modpack-updater/logs/run.log", ".MODPACK-UPDATER/config.json",
            "./.modpack-updater/config.json", "mods/../.modpack-updater/config.json",
            ".modpack-updater./config.json", ".modpack-updater /config.json"})
    void rejectsTheStateDirectoryAndItsPortableAliases(String path) {
        UserError error = assertThrows(UserError.class, () -> SafePath.require(path));
        assertTrue(error.getMessage().contains("reserved state directory"), error.getMessage());
    }

    @ParameterizedTest(name = "\"{0}\" is rejected: {1}")
    @CsvSource({
            "../x, escapes the workspace",
            "a/../../x, escapes the workspace",
            "., escapes the workspace",
            ".., escapes the workspace",
            "/x, not a plain relative path",
            "C:/x, not a plain relative path",
            "a\\b, not a plain relative path"
    })
    void rejectsPathsThatLeaveTheWorkspace(String path, String message) {
        UserError error = assertThrows(UserError.class, () -> SafePath.require(path), path);
        assertTrue(error.getMessage().contains(message), path + ": " + error.getMessage());
    }

    @Test
    void rejectsAnEmptyPath() {
        UserError error = assertThrows(UserError.class, () -> SafePath.require(""));
        assertTrue(error.getMessage().contains("must not be empty"), error.getMessage());
    }

    @Test
    void resolvesInsideTheRoot(@TempDir Path root) {
        assertEquals(root.resolve("mods/x.jar"), SafePath.resolve(root, "mods/x.jar"));
    }
}

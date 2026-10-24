package io.github.deshark42.modpackupdater.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.UserError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreeTest {

    @Test
    void recordsEveryFileSizeAndSortsByPath(@TempDir Path root) throws IOException {
        TestFiles.write(root, "mods/b.jar", "bb");
        TestFiles.write(root, "mods/a.jar", "a");
        TestFiles.write(root, "mmc-pack.json", "{}");

        Map<String, Long> files = Tree.list(root);

        assertEquals(List.of("mmc-pack.json", "mods/a.jar", "mods/b.jar"), List.copyOf(files.keySet()));
        assertEquals(1L, files.get("mods/a.jar"));
        assertEquals(2L, files.get("mods/b.jar"));
    }

    @Test
    void recordsTheEmptyFile(@TempDir Path root) throws IOException {
        TestFiles.write(root, "options.txt", "");

        assertEquals(0L, Tree.list(root).get("options.txt"));
    }

    @Test
    void ignoresEmptyDirectories(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("config/vendingmachine"));

        assertTrue(Tree.list(root).isEmpty());
    }

    @Test
    void ignoresSymbolicLinks(@TempDir Path root) throws IOException {
        TestFiles.write(root, "real.txt", "x");
        Path link = root.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, root.resolve("real.txt"));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "this filesystem cannot create symbolic links, so the rule cannot be checked: " + e);
        }

        assertEquals(List.of("real.txt"), List.copyOf(Tree.list(root).keySet()));
    }

    @Test
    void rejectsAMissingDirectory(@TempDir Path root) {
        Path missing = root.resolve("nowhere");

        UserError error = assertThrows(UserError.class, () -> Tree.list(missing));

        assertTrue(error.getMessage().contains("no such directory: " + missing), error.getMessage());
    }
}

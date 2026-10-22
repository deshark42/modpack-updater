package io.github.deshark42.modpackupdater.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreeTest {

    private static void write(Path root, String name, String content) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void recordsEveryFileSizeAndSortsByPath(@TempDir Path root) throws IOException {
        write(root, "mods/b.jar", "bb");
        write(root, "mods/a.jar", "a");
        write(root, "mmc-pack.json", "{}");
        Map<String, Long> files = Tree.list(root);
        assertEquals(List.of("mmc-pack.json", "mods/a.jar", "mods/b.jar"), List.copyOf(files.keySet()));
        assertEquals(1L, files.get("mods/a.jar"));
        assertEquals(2L, files.get("mods/b.jar"));
    }

    @Test
    void recordsTheEmptyFile(@TempDir Path root) throws IOException {
        write(root, "options.txt", "");
        assertEquals(0L, Tree.list(root).get("options.txt"));
    }

    @Test
    void ignoresEmptyDirectories(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("config/vendingmachine"));
        assertTrue(Tree.list(root).isEmpty());
    }

    @Test
    void ignoresSymbolicLinks(@TempDir Path root) throws IOException {
        write(root, "real.txt", "x");
        try {
            Files.createSymbolicLink(root.resolve("link.txt"), root.resolve("real.txt"));
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        assertEquals(List.of("real.txt"), List.copyOf(Tree.list(root).keySet()));
    }

    @Test
    void rejectsAMissingDirectory(@TempDir Path root) {
        assertThrows(UserError.class, () -> Tree.list(root.resolve("nowhere")));
    }
}

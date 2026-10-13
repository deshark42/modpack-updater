package io.github.deshark42.modpackupdater.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.FileEntry;
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
    void hashesEveryFileAndSortsByPath(@TempDir Path root) throws IOException {
        write(root, "mods/b.jar", "b");
        write(root, "mods/a.jar", "a");
        write(root, "mmc-pack.json", "{}");
        Map<String, FileEntry> files = Tree.scan(root);
        assertEquals(List.of("mmc-pack.json", "mods/a.jar", "mods/b.jar"), List.copyOf(files.keySet()));
        assertEquals(1, files.get("mods/a.jar").size());
        assertEquals(Sha256.ofFile(root.resolve("mods/a.jar")), files.get("mods/a.jar").hash());
    }

    @Test
    void recordsTheEmptyFile(@TempDir Path root) throws IOException {
        write(root, "options.txt", "");
        assertEquals(64, Tree.scan(root).get("options.txt").hash().length());
    }

    @Test
    void ignoresEmptyDirectories(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("config/vendingmachine"));
        assertTrue(Tree.scan(root).isEmpty());
    }

    @Test
    void ignoresSymbolicLinks(@TempDir Path root) throws IOException {
        write(root, "real.txt", "x");
        try {
            Files.createSymbolicLink(root.resolve("link.txt"), root.resolve("real.txt"));
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        assertEquals(List.of("real.txt"), List.copyOf(Tree.scan(root).keySet()));
    }

    @Test
    void rejectsAMissingDirectory(@TempDir Path root) {
        assertThrows(UserError.class, () -> Tree.scan(root.resolve("nowhere")));
    }
}

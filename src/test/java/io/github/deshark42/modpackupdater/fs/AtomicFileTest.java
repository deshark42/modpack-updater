package io.github.deshark42.modpackupdater.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.UserError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFileTest {

    @Test
    void writesAndReadsBackTheContentAsUtf8(@TempDir Path root) {
        Path file = root.resolve("state/配置.json");
        String content = "{\"名字\": \"配置 ✓\"}";

        AtomicFile.write(file, content);

        assertEquals(content, AtomicFile.read(file));
        assertArrayEquals(TestFiles.bytes(content), readAllBytes(file), "the bytes on disk are UTF-8");
    }

    @Test
    void replacesTheWholeContentOnEveryWrite(@TempDir Path root) throws IOException {
        Path file = root.resolve("state/current.json");

        TestFiles.write(root, "state/current.json", "a much longer first content");
        AtomicFile.write(file, "second");

        assertEquals("second", AtomicFile.read(file));
        try (Stream<Path> listing = Files.list(file.getParent())) {
            assertEquals(List.of(file), listing.toList(), "no temporary file is left behind");
        }
    }

    @Test
    void writesAnEmptyFile(@TempDir Path root) {
        Path file = root.resolve("empty.txt");

        AtomicFile.write(file, "");

        assertEquals("", AtomicFile.read(file));
        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void createsTheParentDirectoriesOnTheWay(@TempDir Path root) {
        Path file = root.resolve("a/b/c/current.json");

        AtomicFile.write(file, "{}");

        assertTrue(Files.isDirectory(root.resolve("a/b/c")));
        assertEquals("{}", AtomicFile.read(file));
    }

    @Test
    void refusesToWriteOverADirectory(@TempDir Path root) throws IOException {
        Path directory = root.resolve("state");
        Files.createDirectories(directory);
        TestFiles.write(directory, "keep.txt", "untouched");

        UserError error = assertThrows(UserError.class, () -> AtomicFile.write(directory, "{}"));

        assertTrue(error.getMessage().contains("is a directory"), error.getMessage());
        assertEquals("untouched", Files.readString(directory.resolve("keep.txt")));
        try (Stream<Path> listing = Files.list(root)) {
            assertEquals(List.of(directory), listing.toList(), "no staged write remains");
        }
    }

    @Test
    void reportsThePathWhenTheFileCannotBeRead(@TempDir Path root) {
        Path missing = root.resolve("state/current.json");

        UserError error = assertThrows(UserError.class, () -> AtomicFile.read(missing));

        assertTrue(error.getMessage().startsWith("cannot read " + missing), error.getMessage());
        assertFalse(Files.exists(missing.getParent()));
    }

    @Test
    void movesAFileOverAnExistingTarget(@TempDir Path root) throws IOException {
        Path source = root.resolve("staged");
        Path target = root.resolve("state/current.json");
        TestFiles.write(root, "staged", "new");
        TestFiles.write(root, "state/current.json", "old");

        AtomicFile.move(source, target);

        assertEquals("new", AtomicFile.read(target));
        assertFalse(Files.exists(source), "the source is gone after the move");
    }

    private static byte[] readAllBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new AssertionError("cannot read " + file, e);
        }
    }
}

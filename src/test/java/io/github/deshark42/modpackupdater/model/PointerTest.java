package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PointerTest {

    @Test
    void roundTripsThroughItsOwnJson() {
        Pointer pointer = new Pointer("2.9.0-RC1");
        assertTrue(pointer.toJson().contains("\"version\":" + Pointer.VERSION));
        assertEquals(pointer, Pointer.parse(pointer.toJson(), "latest.json"));
    }

    @Test
    void rejectsUnknownVersion() {
        UserError error = assertThrows(UserError.class,
                () -> Pointer.parse("{\"version\": 9, \"release_id\": \"r\"}", "latest.json"));
        assertTrue(error.getMessage().contains("unsupported version 9, this tool reads version " + Pointer.VERSION),
                error.getMessage());
    }

    @Test
    void rejectsUnsafeReleaseId() {
        UserError error = assertThrows(UserError.class, () -> new Pointer("../x"));
        assertTrue(error.getMessage().contains("invalid release id"), error.getMessage());
    }

    @Test
    void writesAndReadsFromDisk(@TempDir Path root) {
        Path file = Layout.currentFile(root, "client");
        Pointer pointer = new Pointer("2.9.0-RC1");

        pointer.write(file);

        assertTrue(Files.exists(file), file.toString());
        assertEquals(pointer, Pointer.read(file));
    }
}

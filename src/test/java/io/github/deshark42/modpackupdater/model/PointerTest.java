package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PointerTest {

    @Test
    void theWriterIncludesTheCompletePointerSchema() {
        Pointer pointer = new Pointer("2.9.0-RC1");

        String written = pointer.toJson();

        assertEquals(Map.of("version", (long) Pointer.VERSION, "release_id", pointer.releaseId()), Json.parse(written));
    }

    @Test
    void theParserReadsTheWrittenReleaseId() {
        Pointer pointer = new Pointer("2.9.0-RC1");
        String text = pointer.toJson();

        assertEquals(pointer, Pointer.parse(text, "latest.json"));
    }

    @Test
    void anUnknownPointerVersionIsRejected() {
        Map<String, Object> document = Json.asObject(Json.parse(new Pointer("r").toJson()), "pointer");
        long version = Pointer.VERSION + 1L;
        document.put("version", version);
        String text = Json.write(document);

        UserError error = assertThrows(UserError.class, () -> Pointer.parse(text, "latest.json"));

        assertTrue(error.getMessage().contains("unsupported version " + version), error.getMessage());
    }

    @Test
    void aPointerCannotNameAnUnsafeReleaseId() {
        UserError error = assertThrows(UserError.class, () -> new Pointer("../x"));

        assertTrue(error.getMessage().contains("invalid release id"), error.getMessage());
    }

    @Test
    void aWrittenPointerCanBeReadFromDisk(@TempDir Path root) throws Exception {
        Path file = Layout.currentFile(root, "client");
        Pointer pointer = new Pointer("2.9.0-RC1");

        pointer.write(file);

        assertEquals(pointer.toJson(), Files.readString(file));
        assertEquals(pointer, Pointer.read(file));
    }
}

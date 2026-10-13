package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import org.junit.jupiter.api.Test;

class PointerTest {

    @Test
    void roundTrips() {
        Pointer pointer = new Pointer("2.9.0-RC1");
        assertTrue(pointer.toJson().contains("\"version\": 1"));
        assertEquals(pointer, Pointer.parse(pointer.toJson(), "latest.json"));
    }

    @Test
    void rejectsUnknownVersion() {
        assertThrows(UserError.class, () -> Pointer.parse("{\"version\": 9, \"release_id\": \"r\"}", "latest.json"));
    }

    @Test
    void rejectsUnsafeReleaseId() {
        assertThrows(UserError.class, () -> new Pointer("../x"));
    }
}

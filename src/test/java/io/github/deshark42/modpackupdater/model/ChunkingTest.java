package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChunkingTest {

    @Test
    void defaultsAreTheDocumentedParameters() {
        assertEquals(8 * 1024, Chunking.DEFAULT.min());
        assertEquals(32 * 1024, Chunking.DEFAULT.avg());
        assertEquals(128 * 1024, Chunking.DEFAULT.max());
    }

    @Test
    void theMaskLeavesOneShotInAverage() {
        assertEquals(32767L, Chunking.DEFAULT.mask());
    }

    @Test
    void roundTripsThroughJson() {
        Map<String, Object> json = Chunking.DEFAULT.toJson();
        assertEquals(8192L, json.get("min"));
        assertEquals(32768L, json.get("avg"));
        assertEquals(131072L, json.get("max"));
        assertEquals(Chunking.DEFAULT, Chunking.parse(json, "chunking"));
    }

    @Test
    void rejectsAnAverageThatIsNotAPowerOfTwo() {
        UserError error = assertThrows(UserError.class, () -> new Chunking(8192, 40000, 131072));
        assertTrue(error.getMessage().contains("power of two"), error.getMessage());
    }

    @Test
    void rejectsAMinimumThatIsNotBelowTheAverage() {
        UserError error = assertThrows(UserError.class, () -> new Chunking(32768, 32768, 131072));
        assertTrue(error.getMessage().contains("larger than min"), error.getMessage());
    }

    @Test
    void rejectsAMaximumBelowTheAverage() {
        UserError error = assertThrows(UserError.class, () -> new Chunking(8192, 32768, 16384));
        assertTrue(error.getMessage().contains("smaller than avg"), error.getMessage());
    }

    @Test
    void rejectsAMissingField() {
        Map<String, Object> json = Chunking.DEFAULT.toJson();
        json.remove("max");
        assertThrows(UserError.class, () -> Chunking.parse(json, "chunking"));
    }
}

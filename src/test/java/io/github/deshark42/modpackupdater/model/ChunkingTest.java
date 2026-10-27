package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
    void theWriterIncludesEveryChunkingParameter() {
        Map<String, Object> json = Chunking.DEFAULT.toJson();
        assertEquals(Map.of("min", (long) Chunking.DEFAULT.min(), "avg", (long) Chunking.DEFAULT.avg(),
                "max", (long) Chunking.DEFAULT.max()), json);
    }

    @Test
    void theParserReadsTheWrittenChunkingParameters() {
        Map<String, Object> json = Chunking.DEFAULT.toJson();

        assertEquals(Chunking.DEFAULT, Chunking.parse(json, "chunking"));
    }

    @ParameterizedTest(name = "chunking min={0}, avg={1}, max={2} rejects {3}")
    @CsvSource({
            "8192, 40000, 131072, must be a power of two",
            "0, 32768, 131072, min must be positive",
            "32768, 32768, 131072, avg must be larger than min",
            "8192, 32768, 16384, max must not be smaller than avg"
    })
    void invalidChunkingParametersReportTheirCause(int min, int avg, int max, String message) {
        UserError error = assertThrows(UserError.class, () -> new Chunking(min, avg, max));

        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    @Test
    void rejectsAMissingField() {
        Map<String, Object> json = Chunking.DEFAULT.toJson();
        json.remove("max");

        UserError error = assertThrows(UserError.class, () -> Chunking.parse(json, "chunking"));
        assertTrue(error.getMessage().contains("\"max\" must be an integer"), error.getMessage());
    }

    @Test
    void rejectsAFieldThatIsNotAnInteger() {
        Map<String, Object> json = Chunking.DEFAULT.toJson();
        json.put("min", "8k");

        UserError error = assertThrows(UserError.class, () -> Chunking.parse(json, "chunking"));
        assertTrue(error.getMessage().contains("\"min\" must be an integer"), error.getMessage());
    }

    @Test
    void rejectsAFieldOutsideTheIntegerRange() {
        Map<String, Object> json = Chunking.DEFAULT.toJson();
        json.put("min", -1L);

        UserError error = assertThrows(UserError.class, () -> Chunking.parse(json, "chunking"));
        assertTrue(error.getMessage().contains("\"min\" is out of range: -1"), error.getMessage());
    }
}

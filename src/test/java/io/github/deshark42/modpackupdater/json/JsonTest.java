package io.github.deshark42.modpackupdater.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesEveryScalarShape() {
        Map<String, Object> parsed = Json.asObject(
                Json.parse("{\"a\": [1, 2.5, \"x\", true, false, null], \"b\": {}}"), "test");
        assertEquals(Arrays.asList(1L, 2.5, "x", Boolean.TRUE, Boolean.FALSE, null), parsed.get("a"));
        assertEquals(Map.of(), parsed.get("b"));
    }

    @Test
    void unescapesStrings() {
        assertEquals("a\"b\\c\nd\u0001", Json.parse("\"a\\\"b\\\\c\\nd\\u0001\""));
    }

    @Test
    void writesObjectsOfScalarsOnOneLine() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("hash", "abc");
        entry.put("size", 2048L);
        assertEquals("{ \"hash\": \"abc\", \"size\": 2048 }\n", Json.write(entry));
    }

    @Test
    void writesNestedObjectsWithIndentation() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("size", 1L);
        Map<String, Object> innerHolder = new LinkedHashMap<>();
        innerHolder.put("mods/x.jar", inner);
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("files", innerHolder);
        assertEquals("{\n  \"files\": {\n    \"mods/x.jar\": { \"size\": 1 }\n  }\n}\n", Json.write(outer));
    }

    @Test
    void escapesControlCharacters() {
        assertEquals("\"a\\u0001b\"\n", Json.write("a\u0001b"));
    }

    @Test
    void rejectsTrailingContent() {
        assertTrue(assertThrows(Json.JsonException.class, () -> Json.parse("{} x")).getMessage().contains("trailing"));
    }

    @Test
    void rejectsUnterminatedString() {
        assertTrue(assertThrows(Json.JsonException.class, () -> Json.parse("\"abc")).getMessage().contains("unterminated"));
    }

    @Test
    void rejectsRawControlCharacter() {
        assertTrue(assertThrows(Json.JsonException.class, () -> Json.parse("\"a\nb\"")).getMessage().contains("control"));
    }

    @Test
    void rejectsBadEscape() {
        assertTrue(assertThrows(Json.JsonException.class, () -> Json.parse("\"a\\qb\"")).getMessage().contains("escape"));
    }

    @Test
    void rejectsMissingValue() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":}"));
    }
}

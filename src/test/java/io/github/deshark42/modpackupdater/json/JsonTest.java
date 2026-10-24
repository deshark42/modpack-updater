package io.github.deshark42.modpackupdater.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
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
    void roundTripsEveryEscapeThroughItsWriter() {
        String text = "a\"b\\c\nd\re\tf\bg\fh/i\u0001j";

        assertEquals(text, Json.parse(Json.write(text)));
    }

    @Test
    void writesObjectsOfScalarsOnOneLine() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("hash", "abc");
        entry.put("size", 2048L);
        assertEquals("{\"hash\":\"abc\",\"size\":2048}\n", Json.write(entry));
    }

    @Test
    void writesArraysOfScalarsOnOneLine() {
        assertEquals("[1,2,\"x\"]\n", Json.write(List.of(1L, 2L, "x")));
    }

    @Test
    void writesNestedDocumentsOnOneLine() {
        assertEquals("{\"files\":{\"mods/x.jar\":{\"size\":1}}}\n", Json.write(nested()));
    }

    @Test
    void writesPrettyObjectsOneFieldPerLine() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("hash", "abc");
        entry.put("size", 2048L);
        assertEquals("{\n  \"hash\": \"abc\",\n  \"size\": 2048\n}\n", Json.writePretty(entry));
    }

    @Test
    void writesPrettyNestedObjectsOneFieldPerLine() {
        assertEquals(
                "{\n  \"files\": {\n    \"mods/x.jar\": {\n      \"size\": 1\n    }\n  }\n}\n",
                Json.writePretty(nested()));
    }

    @Test
    void escapesControlCharacters() {
        assertEquals("\"a\\u0001b\"\n", Json.write("a\u0001b"));
    }

    @Test
    void leavesHtmlCharactersAlone() {
        assertEquals("\"a<b&c=d'e>f/g\"\n", Json.write("a<b&c=d'e>f/g"));
    }

    @Test
    void writesNullsInsteadOfDroppingThem() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("a", null);
        assertEquals("{\"a\":null}\n", Json.write(entry));
    }

    @Test
    void rejectsTrailingContent() {
        assertTrue(assertThrows(Json.JsonException.class, () -> Json.parse("{} x")).getMessage().contains("malformed JSON"));
    }

    @Test
    void rejectsUnterminatedString() {
        assertTrue(assertThrows(Json.JsonException.class, () -> Json.parse("\"abc")).getMessage().contains("Unterminated string"));
    }

    @Test
    void rejectsRawControlCharacter() {
        assertTrue(assertThrows(Json.JsonException.class, () -> Json.parse("\"a\nb\"")).getMessage().contains("Unescaped control character"));
    }

    @Test
    void rejectsBadEscape() {
        assertTrue(assertThrows(Json.JsonException.class, () -> Json.parse("\"a\\qb\"")).getMessage().contains("Invalid escape"));
    }

    @Test
    void rejectsMissingValue() {
        assertTrue(assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":}")).getMessage().contains("Expected value"));
    }

    @Test
    void rejectsAnEmptyDocument() {
        assertTrue(assertThrows(Json.JsonException.class, () -> Json.parse("  \n")).getMessage().contains("empty"));
    }

    @Test
    void rejectsAnArrayWhereAnObjectIsExpected() {
        UserError error = assertThrows(UserError.class, () -> Json.asObject(Json.parse("[]"), "config.json"));

        assertTrue(error.getMessage().contains("expected an object"), error.getMessage());
    }

    @Test
    void rejectsAnObjectWhereAnArrayIsExpected() {
        UserError error = assertThrows(UserError.class, () -> Json.asArray(Json.parse("{}"), "chunks"));

        assertTrue(error.getMessage().contains("expected an array"), error.getMessage());
    }

    private static Map<String, Object> nested() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("size", 1L);
        Map<String, Object> innerHolder = new LinkedHashMap<>();
        innerHolder.put("mods/x.jar", inner);
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("files", innerHolder);
        return outer;
    }
}

package io.github.deshark42.modpackupdater.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JsonTest {

    @Test
    void parsesEveryScalarShape() {
        Object parsed = Json.parse("{\"a\": [1, 2.5, \"x\", true, false, null], \"b\": {}}");

        assertEquals(Map.of("a", Arrays.asList(1L, 2.5, "x", Boolean.TRUE, Boolean.FALSE, null),
                "b", Map.of()), parsed);
    }

    @Test
    void unescapesStrings() {
        assertEquals("a\"b\\c\nd\u0001", Json.parse("\"a\\\"b\\\\c\\nd\\u0001\""));
    }

    @ParameterizedTest(name = "compact writer preserves {0}")
    @MethodSource("documents")
    void theCompactWriterPreservesTheDocument(String description, Object document) {
        String written = Json.write(document);

        assertEquals(document, Json.parse(written));
        assertTrue(written.endsWith("\n"), "compact documents can be appended as JSON Lines");
        assertFalse(written.substring(0, written.length() - 1).contains("\n"),
                "a compact document occupies one JSON Lines record");
    }

    @ParameterizedTest(name = "pretty writer preserves {0}")
    @MethodSource("documents")
    void thePrettyWriterPreservesTheDocument(String description, Object document) {
        String written = Json.writePretty(document);

        assertEquals(document, Json.parse(written));
    }

    @Test
    void thePrettyWriterPlacesStructuredFieldsOnSeparateLines() {
        Map<String, Object> document = Map.of(
                "name", "client",
                "files", Map.of("mods/a.jar", Map.of("size", 1L)));

        String written = Json.writePretty(document);

        assertEquals(document, Json.parse(written));
        assertTrue(written.stripTrailing().contains("\n"),
                "pretty structured documents contain line breaks beyond the final record terminator");
    }

    private static Stream<Arguments> documents() {
        Map<String, Object> nullable = new LinkedHashMap<>();
        nullable.put("a", null);
        return Stream.of(
                Arguments.of("scalar object", Map.of("hash", "abc", "size", 2048L)),
                Arguments.of("scalar array", List.of(1L, 2L, "x")),
                Arguments.of("nested object", Map.of("files", Map.of("mods/x.jar", Map.of("size", 1L)))),
                Arguments.of("escape characters", "a\"b\\c\nd\re\tf\bg\fh/i\u0001j"),
                Arguments.of("null field", nullable),
                Arguments.of("empty object", Map.of()),
                Arguments.of("empty array", List.of()));
    }

    @Test
    void theWriterEscapesControlCharacters() {
        String written = Json.write("a\u0001b");

        assertFalse(written.contains("\u0001"), "a raw control character is not legal JSON");
        assertEquals("a\u0001b", Json.parse(written));
    }

    @Test
    void theWriterLeavesHtmlCharactersAlone() {
        String text = "a<b&c=d'e>f/g";

        String written = Json.write(text);

        assertTrue(written.contains(text), written);
        assertEquals(text, Json.parse(written));
    }

    @ParameterizedTest(name = "malformed document {0} fails with {1}")
    @MethodSource("malformedDocuments")
    void malformedDocumentsReportTheirCause(String document, String message) {
        Json.JsonException error = assertThrows(Json.JsonException.class, () -> Json.parse(document));

        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    private static Stream<Arguments> malformedDocuments() {
        return Stream.of(
                Arguments.of("{} x", "malformed JSON"),
                Arguments.of("\"abc", "Unterminated string"),
                Arguments.of("\"a\nb\"", "Unescaped control character"),
                Arguments.of("\"a\\qb\"", "Invalid escape"),
                Arguments.of("{\"a\":}", "Expected value"),
                Arguments.of("  \n", "empty"));
    }

    @Test
    void anArrayIsRejectedWhereAnObjectIsExpected() {
        UserError error = assertThrows(UserError.class, () -> Json.asObject(List.of(), "config.json"));

        assertTrue(error.getMessage().contains("expected an object"), error.getMessage());
    }

    @Test
    void anObjectIsRejectedWhereAnArrayIsExpected() {
        UserError error = assertThrows(UserError.class, () -> Json.asArray(Map.of(), "chunks"));

        assertTrue(error.getMessage().contains("expected an array"), error.getMessage());
    }
}

package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.json.Json;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ChunkTableTest {

    private static final String FIRST = Sha256.ofBytes(TestFiles.bytes("first chunk"));
    private static final String SECOND = Sha256.ofBytes(TestFiles.bytes("second chunk"));

    private static ChunkTable table() {
        return new ChunkTable(Chunking.DEFAULT, 70_000, List.of(FIRST, SECOND));
    }

    @Test
    void writesTheDocumentedShape() {
        ChunkTable table = table();

        String json = table.toJson();

        assertEquals(Map.of("version", (long) ChunkTable.VERSION, "algorithm", ChunkTable.ALGORITHM,
                "chunking", table.chunking().toJson(), "size", table.size(), "chunks", table.chunks()),
                Json.parse(json));
    }

    @Test
    void theIndexIdIsTheHashOfTheStoredBytes() {
        ChunkTable table = table();
        byte[] stored = table.bytes();

        String indexId = table.indexId();

        assertEquals(Sha256.ofBytes(stored), indexId);
    }

    @Test
    void theParserReadsEveryWrittenTableField() {
        ChunkTable expected = table();
        String text = expected.toJson();

        assertEquals(expected, ChunkTable.parse(text, "index"));
    }

    @Test
    void theParserReadsAnEmptyFileTable() {
        ChunkTable empty = new ChunkTable(Chunking.DEFAULT, 0, List.of());
        assertEquals(empty, ChunkTable.parse(empty.toJson(), "index"));
    }

    @Test
    void rejectsAnUnknownVersion() {
        Map<String, Object> document = Json.asObject(Json.parse(table().toJson()), "index");
        long unknownVersion = ChunkTable.VERSION + 1L;
        document.put("version", unknownVersion);

        UserError error = assertThrows(UserError.class, () -> ChunkTable.parse(Json.write(document), "index"));

        assertTrue(error.getMessage().contains("unsupported version " + unknownVersion), error.getMessage());
    }

    @Test
    void rejectsAnUnknownAlgorithm() {
        Map<String, Object> document = Json.asObject(Json.parse(table().toJson()), "index");
        document.put("algorithm", "fixed");

        UserError error = assertThrows(UserError.class, () -> ChunkTable.parse(Json.write(document), "index"));
        assertTrue(error.getMessage().contains("unsupported algorithm \"fixed\""), error.getMessage());
    }

    @ParameterizedTest(name = "{0} is rejected: {4}")
    @MethodSource("invalidTables")
    void invalidChunkTablesReportTheirCause(String description, Chunking chunking, long size,
            List<String> chunks, String message) {
        UserError error = assertThrows(UserError.class, () -> new ChunkTable(chunking, size, chunks));

        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    private static Stream<Arguments> invalidTables() {
        return Stream.of(
                Arguments.of("file without chunks", Chunking.DEFAULT, 10L, List.of(), "for 10 bytes has no chunks"),
                Arguments.of("empty file with chunks", Chunking.DEFAULT, 0L, List.of(FIRST), "for an empty file has chunks"),
                Arguments.of("missing chunking", null, 1L, List.of(FIRST), "needs its chunking parameters"),
                Arguments.of("malformed chunk hash", Chunking.DEFAULT, 10L, List.of("abc"), "not a sha256 hash: abc"),
                Arguments.of("negative size", Chunking.DEFAULT, -1L, List.of(FIRST), "negative size: -1"));
    }

    @Test
    void rejectsChunkEntriesThatAreNotStrings() {
        Map<String, Object> document = Json.asObject(Json.parse(table().toJson()), "index");
        document.put("chunks", List.of(1L));

        UserError error = assertThrows(UserError.class, () -> ChunkTable.parse(Json.write(document), "index"));
        assertTrue(error.getMessage().contains("\"chunks\" must hold strings"), error.getMessage());
    }
}

package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sha256;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkTableTest {

    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);

    private static ChunkTable table() {
        return new ChunkTable(Chunking.DEFAULT, 70_000, List.of(A, B));
    }

    @Test
    void writesTheDocumentedShape() {
        String json = table().toJson();
        assertTrue(json.contains("\"version\": 1"), json);
        assertTrue(json.contains("\"algorithm\": \"cdc-gear\""), json);
        assertTrue(json.contains("\"chunking\": { \"min\": 8192, \"avg\": 32768, \"max\": 131072 }"), json);
        assertTrue(json.contains("\"size\": 70000"), json);
        assertTrue(json.contains("\"" + A + "\""), json);
    }

    @Test
    void theIndexIdIsTheHashOfTheStoredBytes() {
        ChunkTable table = table();
        assertEquals(Sha256.ofBytes(table.bytes()), table.indexId());
        assertEquals(table.indexId(),
                ChunkTable.parse(new String(table.bytes(), StandardCharsets.UTF_8), "index").indexId());
    }

    @Test
    void roundTripsThroughItsOwnJson() {
        assertEquals(table(), ChunkTable.parse(table().toJson(), "index"));
    }

    @Test
    void anEmptyFileHasNoChunks() {
        ChunkTable empty = new ChunkTable(Chunking.DEFAULT, 0, List.of());
        assertEquals(empty, ChunkTable.parse(empty.toJson(), "index"));
    }

    @Test
    void rejectsAnUnknownVersion() {
        UserError error = assertThrows(UserError.class, () -> ChunkTable.parse("{\"version\": 2,"
                + " \"algorithm\": \"cdc-gear\", \"chunking\": {\"min\": 8192, \"avg\": 32768,"
                + " \"max\": 131072}, \"size\": 0, \"chunks\": []}", "index"));
        assertTrue(error.getMessage().contains("version 2"), error.getMessage());
    }

    @Test
    void rejectsAnUnknownAlgorithm() {
        UserError error = assertThrows(UserError.class, () -> ChunkTable.parse("{\"version\": 1,"
                + " \"algorithm\": \"fixed\", \"chunking\": {\"min\": 8192, \"avg\": 32768,"
                + " \"max\": 131072}, \"size\": 0, \"chunks\": []}", "index"));
        assertTrue(error.getMessage().contains("fixed"), error.getMessage());
    }

    @Test
    void rejectsAFileWithNoChunks() {
        UserError error = assertThrows(UserError.class,
                () -> new ChunkTable(Chunking.DEFAULT, 10, List.of()));
        assertTrue(error.getMessage().contains("no chunks"), error.getMessage());
    }

    @Test
    void rejectsChunksForAnEmptyFile() {
        UserError error = assertThrows(UserError.class,
                () -> new ChunkTable(Chunking.DEFAULT, 0, List.of(A)));
        assertTrue(error.getMessage().contains("has chunks"), error.getMessage());
    }

    @Test
    void rejectsAMalformedChunkHash() {
        assertThrows(UserError.class, () -> new ChunkTable(Chunking.DEFAULT, 10, List.of("abc")));
    }

    @Test
    void rejectsANegativeSize() {
        assertThrows(UserError.class, () -> new ChunkTable(Chunking.DEFAULT, -1, List.of(A)));
    }

    @Test
    void theSameContentGetsTheSameId() {
        assertEquals(table().indexId(), new ChunkTable(Chunking.DEFAULT, 70_000, List.of(A, B)).indexId());
    }
}

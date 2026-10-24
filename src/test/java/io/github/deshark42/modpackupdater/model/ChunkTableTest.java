package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sha256;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkTableTest {

    private static final String FIRST = Sha256.ofBytes(TestFiles.bytes("first chunk"));
    private static final String SECOND = Sha256.ofBytes(TestFiles.bytes("second chunk"));

    private static ChunkTable table() {
        return new ChunkTable(Chunking.DEFAULT, 70_000, List.of(FIRST, SECOND));
    }

    @Test
    void writesTheDocumentedShape() {
        String json = table().toJson();
        assertTrue(json.contains("\"version\":" + ChunkTable.VERSION), json);
        assertTrue(json.contains("\"algorithm\":\"" + ChunkTable.ALGORITHM + "\""), json);
        assertTrue(json.contains("\"chunking\":{\"min\":8192,\"avg\":32768,\"max\":131072}"), json);
        assertTrue(json.contains("\"size\":70000"), json);
        assertTrue(json.contains("\"" + FIRST + "\""), json);
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
        assertTrue(error.getMessage().contains("unsupported version 2"), error.getMessage());
    }

    @Test
    void rejectsAnUnknownAlgorithm() {
        UserError error = assertThrows(UserError.class, () -> ChunkTable.parse("{\"version\": 1,"
                + " \"algorithm\": \"fixed\", \"chunking\": {\"min\": 8192, \"avg\": 32768,"
                + " \"max\": 131072}, \"size\": 0, \"chunks\": []}", "index"));
        assertTrue(error.getMessage().contains("unsupported algorithm \"fixed\""), error.getMessage());
    }

    @Test
    void rejectsAFileWithNoChunks() {
        UserError error = assertThrows(UserError.class,
                () -> new ChunkTable(Chunking.DEFAULT, 10, List.of()));
        assertTrue(error.getMessage().contains("for 10 bytes has no chunks"), error.getMessage());
    }

    @Test
    void rejectsChunksForAnEmptyFile() {
        UserError error = assertThrows(UserError.class,
                () -> new ChunkTable(Chunking.DEFAULT, 0, List.of(FIRST)));
        assertTrue(error.getMessage().contains("for an empty file has chunks"), error.getMessage());
    }

    @Test
    void rejectsATableWithoutItsChunkingParameters() {
        UserError error = assertThrows(UserError.class, () -> new ChunkTable(null, 1, List.of(FIRST)));
        assertTrue(error.getMessage().contains("needs its chunking parameters"), error.getMessage());
    }

    @Test
    void rejectsAMalformedChunkHash() {
        UserError error = assertThrows(UserError.class, () -> new ChunkTable(Chunking.DEFAULT, 10, List.of("abc")));
        assertTrue(error.getMessage().contains("not a sha256 hash: abc"), error.getMessage());
    }

    @Test
    void rejectsANegativeSize() {
        UserError error = assertThrows(UserError.class, () -> new ChunkTable(Chunking.DEFAULT, -1, List.of(FIRST)));
        assertTrue(error.getMessage().contains("negative size: -1"), error.getMessage());
    }

    @Test
    void rejectsChunkEntriesThatAreNotStrings() {
        UserError error = assertThrows(UserError.class, () -> ChunkTable.parse("{\"version\": 1,"
                + " \"algorithm\": \"cdc-gear\", \"chunking\": {\"min\": 8192, \"avg\": 32768,"
                + " \"max\": 131072}, \"size\": 1, \"chunks\": [1]}", "index"));
        assertTrue(error.getMessage().contains("\"chunks\" must hold strings"), error.getMessage());
    }
}

package io.github.deshark42.modpackupdater.model;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ordered chunks one file content splits into, with the parameters that produced them so a
 * subscriber can split its own copy the same way.
 *
 * <p>The table is content addressed by its own serialized bytes: the same file content always
 * produces the same table, so two tags or releases that carry the file share one table object. The
 * chunk list stays out of the manifest, which only carries the table's id.
 */
public record ChunkTable(Chunking chunking, long size, List<String> chunks) {

    public static final int VERSION = 1;
    public static final String ALGORITHM = "cdc-gear";

    public ChunkTable {
        if (chunking == null) {
            throw new UserError("a chunk table needs its chunking parameters");
        }
        if (size < 0) {
            throw new UserError("a chunk table has a negative size: " + size);
        }
        List<String> copy = List.copyOf(chunks);
        for (String chunk : copy) {
            Manifest.requireHash(chunk);
        }
        if (size == 0 && !copy.isEmpty()) {
            throw new UserError("a chunk table for an empty file has chunks");
        }
        if (size > 0 && copy.isEmpty()) {
            throw new UserError("a chunk table for " + size + " bytes has no chunks");
        }
        chunks = copy;
    }

    /** The sha256 of the serialized table: the name this table is stored under. */
    public String indexId() {
        return Sha256.ofBytes(bytes());
    }

    public byte[] bytes() {
        return toJson().getBytes(StandardCharsets.UTF_8);
    }

    public String toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", VERSION);
        out.put("algorithm", ALGORITHM);
        out.put("chunking", chunking.toJson());
        out.put("size", size);
        out.put("chunks", chunks);
        return Json.write(out);
    }

    public static ChunkTable parse(String text, String what) {
        Map<String, Object> object = Json.asObject(Json.parse(text), what);
        long version = Fields.longValue(object, "version", what);
        if (version != VERSION) {
            throw new UserError(what + ": unsupported version " + version + ", this tool reads version " + VERSION);
        }
        String algorithm = Fields.string(object, "algorithm", what);
        if (!ALGORITHM.equals(algorithm)) {
            throw new UserError(what + ": unsupported algorithm \"" + algorithm + "\", this tool knows " + ALGORITHM);
        }
        Chunking chunking = Chunking.parse(Fields.object(object, "chunking", what), what + ": \"chunking\"");
        List<String> chunks = new ArrayList<>();
        for (Object chunk : Json.asArray(object.get("chunks"), what + ": \"chunks\"")) {
            if (!(chunk instanceof String hash)) {
                throw new UserError(what + ": \"chunks\" must hold strings");
            }
            chunks.add(hash);
        }
        return new ChunkTable(chunking, Fields.longValue(object, "size", what), chunks);
    }
}

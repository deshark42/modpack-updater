package io.github.deshark42.modpackupdater.model;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import io.github.deshark42.modpackupdater.json.Json;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "Which release is current": the remote metadata/<tag>/latest.json and the local
 * .modpack-updater/<tag>/current.json have the same shape.
 */
public record Pointer(String releaseId) {

    public static final int VERSION = 1;

    public Pointer {
        releaseId = Names.requireReleaseId(releaseId);
    }

    public static Pointer read(Path file) {
        return parse(AtomicFile.read(file), file.toString());
    }

    public void write(Path file) {
        AtomicFile.write(file, toJson());
    }

    public static Pointer parse(String text, String what) {
        Map<String, Object> object = Json.asObject(Json.parse(text), what);
        long version = Fields.longValue(object, "version", what);
        if (version != VERSION) {
            throw new UserError(what + ": unsupported version " + version + ", this tool reads version " + VERSION);
        }
        return new Pointer(Fields.string(object, "release_id", what));
    }

    public String toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", VERSION);
        out.put("release_id", releaseId);
        return Json.write(out);
    }
}

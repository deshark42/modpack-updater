package io.github.deshark42.modpackupdater.model;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import io.github.deshark42.modpackupdater.json.Json;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The complete list of tracked files for one release of one tag. Version 2 records, per file, the
 * id of the chunk table that splits it as well as the whole-content hash.
 */
public record Manifest(String tag, String releaseId, Instant publishedAt, String hashAlgorithm,
                       Map<String, FileEntry> files) {

    public static final int VERSION = 2;
    public static final String SHA256 = "sha256";

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public Manifest {
        tag = Names.requireTag(tag);
        releaseId = Names.requireReleaseId(releaseId);
        if (publishedAt == null) {
            throw new UserError("a manifest needs \"published_at\"");
        }
        if (!SHA256.equals(hashAlgorithm)) {
            throw new UserError("unsupported hash_algorithm \"" + hashAlgorithm + "\": this tool only knows " + SHA256);
        }
        files.keySet().forEach(SafePath::require);
        files = Collections.unmodifiableMap(new TreeMap<>(files));
    }

    public long totalSize() {
        long total = 0;
        for (FileEntry entry : files.values()) {
            total += entry.size();
        }
        return total;
    }

    public static void requireHash(String hash) {
        if (hash == null || !SHA256_HEX.matcher(hash).matches()) {
            throw new UserError("not a sha256 hash: " + hash);
        }
    }

    public static void requireIndexId(String indexId) {
        if (indexId == null || !SHA256_HEX.matcher(indexId).matches()) {
            throw new UserError("not a chunk table id: " + indexId);
        }
    }

    public static Manifest read(Path file) {
        return parse(AtomicFile.read(file), file.toString());
    }

    public void write(Path file) {
        AtomicFile.write(file, toJson());
    }

    public static Manifest parse(String text, String what) {
        Map<String, Object> object = Json.asObject(Json.parse(text), what);
        long version = Fields.longValue(object, "version", what);
        if (version != VERSION) {
            throw new UserError(what + ": unsupported version " + version + ", this tool reads version " + VERSION);
        }
        Map<String, FileEntry> files = new TreeMap<>();
        for (Map.Entry<String, Object> entry : Fields.object(object, "files", what).entrySet()) {
            String path = SafePath.require(entry.getKey());
            Map<String, Object> value = Json.asObject(entry.getValue(), what + ": files[\"" + path + "\"]");
            String entryWhat = what + ": files[\"" + path + "\"]";
            String hash = Fields.string(value, "hash", entryWhat);
            String indexId = Fields.string(value, "index_id", entryWhat);
            files.put(path, new FileEntry(hash, Fields.longValue(value, "size", entryWhat), indexId));
        }
        String publishedAt = Fields.string(object, "published_at", what);
        return new Manifest(
                Fields.string(object, "tag", what),
                Fields.string(object, "release_id", what),
                parseInstant(publishedAt, what),
                Fields.string(object, "hash_algorithm", what),
                files);
    }

    public String toJson() {
        Map<String, Object> filesOut = new LinkedHashMap<>();
        for (Map.Entry<String, FileEntry> entry : files.entrySet()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("hash", entry.getValue().hash());
            value.put("size", entry.getValue().size());
            value.put("index_id", entry.getValue().indexId());
            filesOut.put(entry.getKey(), value);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", VERSION);
        out.put("tag", tag);
        out.put("release_id", releaseId);
        out.put("published_at", DateTimeFormatter.ISO_INSTANT.format(publishedAt.truncatedTo(ChronoUnit.SECONDS)));
        out.put("hash_algorithm", hashAlgorithm);
        out.put("files", filesOut);
        return Json.write(out);
    }

    private static Instant parseInstant(String value, String what) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new UserError(what + ": \"published_at\" is not an ISO-8601 instant: " + value);
        }
    }
}

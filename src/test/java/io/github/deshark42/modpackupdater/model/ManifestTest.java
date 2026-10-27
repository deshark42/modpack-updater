package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ManifestTest {

    private static final String HASH = Sha256.ofBytes(TestFiles.bytes("manifest hash"));
    private static final String INDEX_ID = Sha256.ofBytes(TestFiles.bytes("manifest index"));
    private static final Instant PUBLISHED_AT = Instant.parse("2026-10-14T01:30:00Z");

    private static Manifest manifest() {
        return new Manifest("client", "2.9.0-RC1", PUBLISHED_AT, Manifest.SHA256,
                Map.of("mods/x.jar", new FileEntry(HASH, 12, INDEX_ID)));
    }

    private static Map<String, Object> document() {
        return Json.asObject(Json.parse(manifest().toJson()), "manifest");
    }

    private static Map<String, Object> entry() {
        return Json.asObject(Json.asObject(document().get("files"), "files").get("mods/x.jar"), "entry");
    }

    @Test
    void theParserReadsEveryManifestField() {
        Manifest expected = manifest();

        Manifest parsed = Manifest.parse(expected.toJson(), "m.json");

        assertEquals(expected, parsed);
    }

    @Test
    void theWriterIncludesTheCompleteManifestSchema() {
        Manifest manifest = manifest();

        String written = manifest.toJson();

        assertEquals(Map.of("version", (long) Manifest.VERSION, "tag", manifest.tag(),
                "release_id", manifest.releaseId(), "published_at", PUBLISHED_AT.toString(),
                "hash_algorithm", Manifest.SHA256, "files", Map.of("mods/x.jar",
                        Map.of("hash", HASH, "size", 12L, "index_id", INDEX_ID))), Json.parse(written));
    }

    @Test
    void theTotalSizeIncludesEveryFile() {
        Manifest manifest = new Manifest("client", "r", PUBLISHED_AT, Manifest.SHA256,
                Map.of("a", new FileEntry(HASH, 12, INDEX_ID), "b", new FileEntry(HASH, 7, INDEX_ID)));

        assertEquals(19, manifest.totalSize());
    }

    @Test
    void filesAreSortedByPath() {
        Map<String, Object> document = document();
        document.put("files", Map.of("mods/z.jar", entry(), "config/a.cfg", entry()));

        Manifest manifest = Manifest.parse(Json.write(document), "m.json");

        assertEquals(List.of("config/a.cfg", "mods/z.jar"), new ArrayList<>(manifest.files().keySet()));
    }

    @Test
    void theWriterOrdersFilesByPath() {
        Manifest manifest = new Manifest("client", "r", PUBLISHED_AT, Manifest.SHA256,
                Map.of("mods/z.jar", new FileEntry(HASH, 1, INDEX_ID),
                        "config/a.cfg", new FileEntry(HASH, 1, INDEX_ID)));

        String written = manifest.toJson();

        Map<String, Object> files = Json.asObject(Json.asObject(Json.parse(written), "manifest").get("files"), "files");
        assertEquals(List.of("config/a.cfg", "mods/z.jar"), new ArrayList<>(files.keySet()));
    }

    @ParameterizedTest(name = "manifest field {0} rejects {1}")
    @MethodSource("invalidMetadata")
    void invalidManifestMetadataReportsItsCause(String field, Object value, String message) {
        Map<String, Object> document = document();
        document.put(field, value);
        String text = Json.write(document);

        UserError error = assertThrows(UserError.class, () -> Manifest.parse(text, "m.json"));

        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    private static Stream<Arguments> invalidMetadata() {
        return Stream.of(
                Arguments.of("version", Manifest.VERSION - 1L, "unsupported version " + (Manifest.VERSION - 1)),
                Arguments.of("hash_algorithm", "md5", "unsupported hash_algorithm \"md5\""),
                Arguments.of("published_at", "2026-10-14", "not an ISO-8601 instant"));
    }

    @ParameterizedTest(name = "entry field {0} rejects {1}")
    @MethodSource("invalidEntries")
    void invalidFileEntriesReportTheirCause(String field, Object value, String message) {
        Map<String, Object> entry = entry();
        if (value == null) {
            entry.remove(field);
        } else {
            entry.put(field, value);
        }
        Map<String, Object> document = document();
        document.put("files", Map.of("mods/x.jar", entry));
        String text = Json.write(document);

        UserError error = assertThrows(UserError.class, () -> Manifest.parse(text, "m.json"));

        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    private static Stream<Arguments> invalidEntries() {
        return Stream.of(
                Arguments.of("index_id", null, "missing a string \"index_id\""),
                Arguments.of("index_id", "abc", "not a chunk table id: abc"),
                Arguments.of("hash", "abc", "not a sha256 hash: abc"),
                Arguments.of("size", -1L, "negative size: -1"));
    }

    @Test
    void aParsedManifestCannotNameAPathOutsideTheWorkspace() {
        Map<String, Object> document = document();
        document.put("files", Map.of("../escape", entry()));
        String text = Json.write(document);

        UserError error = assertThrows(UserError.class, () -> Manifest.parse(text, "m.json"));

        assertTrue(error.getMessage().contains("manifest path"), error.getMessage());
    }

    @Test
    void aDirectlyConstructedManifestCannotReplaceUpdaterState() {
        UserError error = assertThrows(UserError.class, () -> new Manifest("client", "r", PUBLISHED_AT,
                Manifest.SHA256, Map.of(Layout.STATE_DIR + "/" + Layout.CONFIG, new FileEntry(HASH, 1, INDEX_ID))));

        assertTrue(error.getMessage().contains("reserved state directory"), error.getMessage());
    }

    @Test
    void redundantSegmentsAreNormalizedWhileParsing() {
        Map<String, Object> document = document();
        document.put("files", Map.of("mods/./x.jar", entry()));

        Manifest manifest = Manifest.parse(Json.write(document), "m.json");

        assertEquals(List.of("mods/x.jar"), new ArrayList<>(manifest.files().keySet()));
    }

    @Test
    void aWrittenManifestCanBeReadFromDisk(@TempDir Path root) throws Exception {
        Path file = Layout.releaseFile(root, "client", "2.9.0-RC1");
        Manifest manifest = manifest();

        manifest.write(file);

        assertEquals(manifest.toJson(), Files.readString(file));
        assertEquals(manifest, Manifest.read(file));
    }
}

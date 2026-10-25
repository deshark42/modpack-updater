package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sha256;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ManifestTest {

    private static final String HASH = Sha256.ofBytes(TestFiles.bytes("manifest hash"));
    private static final String INDEX_ID = Sha256.ofBytes(TestFiles.bytes("manifest index"));

    private static String manifest(String files) {
        return "{\"version\": 2, \"tag\": \"client\", \"release_id\": \"2.9.0-RC1\","
                + " \"published_at\": \"2026-10-14T01:30:00Z\", \"hash_algorithm\": \"sha256\","
                + " \"files\": {" + files + "}}";
    }

    private static String entry(String path, String hash, long size) {
        return "\"" + path.replace("\\", "\\\\") + "\": {\"hash\": \"" + hash + "\", \"size\": " + size
                + ", \"index_id\": \"" + INDEX_ID + "\"}";
    }

    @Test
    void parsesEveryField() {
        Manifest manifest = Manifest.parse(manifest(entry("mods/x.jar", HASH, 12)), "m.json");
        assertEquals("client", manifest.tag());
        assertEquals("2.9.0-RC1", manifest.releaseId());
        assertEquals(Instant.parse("2026-10-14T01:30:00Z"), manifest.publishedAt());
        assertEquals(Manifest.SHA256, manifest.hashAlgorithm());
        assertEquals(new FileEntry(HASH, 12, INDEX_ID), manifest.files().get("mods/x.jar"));
        assertEquals(12, manifest.totalSize());
    }

    @Test
    void roundTripsThroughItsOwnJson() {
        Manifest manifest = Manifest.parse(manifest(entry("mods/x.jar", HASH, 12)), "m.json");
        assertEquals(manifest, Manifest.parse(manifest.toJson(), "round trip"));
        assertTrue(manifest.toJson().contains("\"version\":" + Manifest.VERSION));
        assertTrue(manifest.toJson().contains("\"hash_algorithm\":\"" + Manifest.SHA256 + "\""));
        assertTrue(manifest.toJson().contains("\"index_id\":\"" + INDEX_ID + "\""));
    }

    @Test
    void sortsFilesByPath() {
        Manifest manifest = Manifest.parse(
                manifest(entry("mods/z.jar", HASH, 1) + ", " + entry("config/a.cfg", HASH, 1)), "m.json");
        assertEquals(List.of("config/a.cfg", "mods/z.jar"), new ArrayList<>(manifest.files().keySet()));
        assertTrue(manifest.toJson().indexOf("config/a.cfg") < manifest.toJson().indexOf("mods/z.jar"));
    }

    @Test
    void rejectsTheOlderVersion() {
        UserError error = assertThrows(UserError.class, () -> Manifest.parse(
                "{\"version\": 1, \"tag\": \"client\", \"release_id\": \"r\","
                        + " \"published_at\": \"2026-10-14T01:30:00Z\", \"hash_algorithm\": \"sha256\","
                        + " \"files\": {}}",
                "m.json"));
        assertTrue(error.getMessage().contains("unsupported version 1"), error.getMessage());
    }

    @Test
    void rejectsAnEntryWithoutAChunkTable() {
        UserError error = assertThrows(UserError.class, () -> Manifest.parse(
                manifest("\"mods/x.jar\": {\"hash\": \"" + HASH + "\", \"size\": 1}"), "m.json"));
        assertTrue(error.getMessage().contains("missing a string \"index_id\""), error.getMessage());
    }

    @Test
    void rejectsMalformedChunkTableId() {
        UserError error = assertThrows(UserError.class, () -> Manifest.parse(
                manifest("\"mods/x.jar\": {\"hash\": \"" + HASH + "\", \"size\": 1, \"index_id\": \"abc\"}"),
                "m.json"));
        assertTrue(error.getMessage().contains("not a chunk table id: abc"), error.getMessage());
    }

    @Test
    void rejectsAnUnsupportedHashAlgorithm() {
        UserError error = assertThrows(UserError.class, () -> Manifest.parse(
                "{\"version\": 2, \"tag\": \"client\", \"release_id\": \"r\","
                        + " \"published_at\": \"2026-10-14T01:30:00Z\", \"hash_algorithm\": \"md5\", \"files\": {}}",
                "m.json"));
        assertTrue(error.getMessage().contains("unsupported hash_algorithm \"md5\""), error.getMessage());
    }

    @Test
    void rejectsATimestampThatIsNotAnInstant() {
        UserError error = assertThrows(UserError.class, () -> Manifest.parse(
                "{\"version\": 2, \"tag\": \"client\", \"release_id\": \"r\","
                        + " \"published_at\": \"2026-10-14\", \"hash_algorithm\": \"sha256\", \"files\": {}}",
                "m.json"));
        assertTrue(error.getMessage().contains("not an ISO-8601 instant"), error.getMessage());
    }

    @Test
    void rejectsMalformedHash() {
        UserError error = assertThrows(UserError.class,
                () -> Manifest.parse(manifest(entry("mods/x.jar", "abc", 1)), "m.json"));
        assertTrue(error.getMessage().contains("not a sha256 hash: abc"), error.getMessage());
    }

    @ParameterizedTest(name = "\"{0}\" is not a manifest path")
    @ValueSource(strings = {"../escape", "/etc/passwd", "a\\b", "C:/x", "mods/../../x", ""})
    void rejectsPathsThatLeaveTheWorkspace(String path) {
        UserError error = assertThrows(UserError.class,
                () -> Manifest.parse(manifest(entry(path, HASH, 1)), "m.json"), path);
        assertTrue(error.getMessage().contains("manifest path"), path + ": " + error.getMessage());
    }

    @Test
    void rejectsReservedPathsWhenConstructedDirectly() {
        UserError error = assertThrows(UserError.class, () -> new Manifest("client", "r", Instant.EPOCH,
                Manifest.SHA256, Map.of(Layout.STATE_DIR + "/" + Layout.CONFIG,
                        new FileEntry(HASH, 1, INDEX_ID))));

        assertTrue(error.getMessage().contains("reserved state directory"), error.getMessage());
    }

    @Test
    void normalizesRedundantSegments() {
        Manifest manifest = Manifest.parse(manifest(entry("mods/./x.jar", HASH, 1)), "m.json");
        assertEquals(List.of("mods/x.jar"), new ArrayList<>(manifest.files().keySet()));
    }

    @Test
    void rejectsNegativeSize() {
        UserError error = assertThrows(UserError.class,
                () -> Manifest.parse(manifest(entry("mods/x.jar", HASH, -1)), "m.json"));
        assertTrue(error.getMessage().contains("negative size: -1"), error.getMessage());
    }

    @Test
    void writesAndReadsFromDisk(@TempDir Path root) {
        Path file = Layout.releaseFile(root, "client", "2.9.0-RC1");
        Manifest manifest = Manifest.parse(manifest(entry("mods/x.jar", HASH, 12)), "m.json");
        manifest.write(file);
        assertTrue(Files.exists(file), file.toString());
        assertEquals(manifest, Manifest.read(file));
    }
}

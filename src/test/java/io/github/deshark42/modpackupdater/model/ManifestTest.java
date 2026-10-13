package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestTest {

    private static final String HASH = "a".repeat(64);

    private static String manifest(String files) {
        return "{\"version\": 1, \"tag\": \"client\", \"release_id\": \"2.9.0-RC1\","
                + " \"published_at\": \"2026-10-14T01:30:00Z\", \"hash_algorithm\": \"sha256\","
                + " \"files\": {" + files + "}}";
    }

    private static String entry(String path, String hash, long size) {
        return "\"" + path.replace("\\", "\\\\") + "\": {\"hash\": \"" + hash + "\", \"size\": " + size + "}";
    }

    @Test
    void parsesEveryField() {
        Manifest manifest = Manifest.parse(manifest(entry("mods/x.jar", HASH, 12)), "m.json");
        assertEquals("client", manifest.tag());
        assertEquals("2.9.0-RC1", manifest.releaseId());
        assertEquals(Instant.parse("2026-10-14T01:30:00Z"), manifest.publishedAt());
        assertEquals(Manifest.SHA256, manifest.hashAlgorithm());
        assertEquals(new FileEntry(HASH, 12), manifest.files().get("mods/x.jar"));
        assertEquals(12, manifest.totalSize());
    }

    @Test
    void roundTripsThroughItsOwnJson() {
        Manifest manifest = Manifest.parse(manifest(entry("mods/x.jar", HASH, 12)), "m.json");
        assertEquals(manifest, Manifest.parse(manifest.toJson(), "round trip"));
        assertTrue(manifest.toJson().contains("\"version\": 1"));
        assertTrue(manifest.toJson().contains("\"hash_algorithm\": \"sha256\""));
    }

    @Test
    void sortsFilesByPath() {
        Manifest manifest = Manifest.parse(
                manifest(entry("mods/z.jar", HASH, 1) + ", " + entry("config/a.cfg", HASH, 1)), "m.json");
        assertEquals(List.of("config/a.cfg", "mods/z.jar"), new ArrayList<>(manifest.files().keySet()));
        assertTrue(manifest.toJson().indexOf("config/a.cfg") < manifest.toJson().indexOf("mods/z.jar"));
    }

    @Test
    void rejectsUnknownVersion() {
        UserError error = assertThrows(UserError.class, () -> Manifest.parse(
                "{\"version\": 2, \"tag\": \"client\", \"release_id\": \"r\","
                        + " \"published_at\": \"2026-10-14T01:30:00Z\", \"hash_algorithm\": \"sha256\", \"files\": {}}",
                "m.json"));
        assertTrue(error.getMessage().contains("version 2"));
    }

    @Test
    void rejectsUnknownAlgorithm() {
        UserError error = assertThrows(UserError.class, () -> Manifest.parse(
                "{\"version\": 1, \"tag\": \"client\", \"release_id\": \"r\","
                        + " \"published_at\": \"2026-10-14T01:30:00Z\", \"hash_algorithm\": \"md5\", \"files\": {}}",
                "m.json"));
        assertTrue(error.getMessage().contains("md5"));
    }

    @Test
    void rejectsMalformedHash() {
        assertThrows(UserError.class, () -> Manifest.parse(manifest(entry("mods/x.jar", "abc", 1)), "m.json"));
    }

    @Test
    void rejectsPathsThatLeaveTheWorkspace() {
        for (String path : new String[]{"../escape", "/etc/passwd", "a\\b", "C:/x", "mods/../../x", ""}) {
            assertThrows(UserError.class, () -> Manifest.parse(manifest(entry(path, HASH, 1)), "m.json"), path);
        }
    }

    @Test
    void normalizesRedundantSegments() {
        Manifest manifest = Manifest.parse(manifest(entry("mods/./x.jar", HASH, 1)), "m.json");
        assertEquals(List.of("mods/x.jar"), new ArrayList<>(manifest.files().keySet()));
    }

    @Test
    void rejectsNegativeSize() {
        assertThrows(UserError.class, () -> Manifest.parse(manifest(entry("mods/x.jar", HASH, -1)), "m.json"));
    }

    @Test
    void writesAndReadsFromDisk(@TempDir Path root) {
        Path file = root.resolve("releases/2.9.0-RC1.json");
        Manifest manifest = Manifest.parse(manifest(entry("mods/x.jar", HASH, 12)), "m.json");
        manifest.write(file);
        assertTrue(Files.exists(file));
        assertEquals(manifest, Manifest.read(file));
    }
}

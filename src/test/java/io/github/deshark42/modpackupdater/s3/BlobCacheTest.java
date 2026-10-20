package io.github.deshark42.modpackupdater.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.json.Json;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlobCacheTest {

    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    private static Config config(String bucket) {
        return Config.parse("{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"" + bucket + "\","
                + " \"prefix\": \"gtnh\", \"path_style\": true}", "config.json");
    }

    private static BlobCache open(Path workspace) {
        return BlobCache.open(workspace, config("pack"), CLOCK);
    }

    private static List<Map<String, Object>> entries(Path workspace) throws IOException {
        List<String> lines = Files.readAllLines(Layout.knownBlobsFile(workspace), StandardCharsets.UTF_8);
        return lines.subList(1, lines.size()).stream()
                .map(line -> Json.asObject(Json.parse(line), "entry"))
                .toList();
    }

    @Test
    void writesNothingUntilItKnowsABlob(@TempDir Path workspace) {
        BlobCache cache = open(workspace);

        assertFalse(cache.known(HASH_A));
        assertFalse(Files.exists(Layout.knownBlobsFile(workspace)));
    }

    @Test
    void writesOneJsonObjectPerLine(@TempDir Path workspace) throws IOException {
        BlobCache cache = open(workspace);
        cache.remember(HASH_A);
        cache.remember(HASH_B);

        List<String> lines = Files.readAllLines(Layout.knownBlobsFile(workspace), StandardCharsets.UTF_8);
        assertEquals(3, lines.size());

        Map<String, Object> header = Json.asObject(Json.parse(lines.get(0)), "header");
        assertEquals("v1", header.get("version"));
        assertTrue(header.get("fingerprint") instanceof String);
        assertTrue(header.get("createdAt") instanceof Long);
        assertEquals(Map.of("hash", HASH_A), Json.asObject(Json.parse(lines.get(1)), "entry"));
        assertEquals(Map.of("hash", HASH_B), Json.asObject(Json.parse(lines.get(2)), "entry"));
    }

    @Test
    void remembersBlobsAcrossInstances(@TempDir Path workspace) {
        open(workspace).remember(HASH_A);

        BlobCache reopened = open(workspace);

        assertTrue(reopened.known(HASH_A));
        assertFalse(reopened.known(HASH_B));
    }

    @Test
    void doesNotWriteAnEntryTwice(@TempDir Path workspace) throws IOException {
        BlobCache cache = open(workspace);
        cache.remember(HASH_A);
        cache.remember(HASH_A);

        assertEquals(1L, entries(workspace).stream()
                .filter(entry -> HASH_A.equals(entry.get("hash")))
                .count());
    }

    @Test
    void dropsTheCacheAfterItsMaximumAge(@TempDir Path workspace) {
        open(workspace).remember(HASH_A);

        Clock fresh = Clock.fixed(NOW.plus(Duration.ofDays(29)), ZoneOffset.UTC);
        Clock stale = Clock.fixed(NOW.plus(Duration.ofDays(31)), ZoneOffset.UTC);

        assertTrue(BlobCache.open(workspace, config("pack"), fresh).known(HASH_A));
        assertFalse(BlobCache.open(workspace, config("pack"), stale).known(HASH_A));
    }

    @Test
    void dropsTheCacheOfAnotherBucketLocation(@TempDir Path workspace) {
        open(workspace).remember(HASH_A);

        assertFalse(BlobCache.open(workspace, config("other"), CLOCK).known(HASH_A));
    }

    @Test
    void dropsACorruptedCacheAndBuildsANewOne(@TempDir Path workspace) throws IOException {
        open(workspace).remember(HASH_A);
        Files.writeString(Layout.knownBlobsFile(workspace), "not a cache\n", StandardCharsets.UTF_8);

        BlobCache corrupted = open(workspace);
        assertFalse(corrupted.known(HASH_A));

        corrupted.remember(HASH_A);
        assertTrue(open(workspace).known(HASH_A));
    }

    @Test
    void dropsACacheWithAMalformedEntry(@TempDir Path workspace) throws IOException {
        open(workspace).remember(HASH_A);
        Files.writeString(Layout.knownBlobsFile(workspace), "{\"hash\": \"not-a-hash\"}\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        assertFalse(open(workspace).known(HASH_A));
    }

    @Test
    void startsANewCacheAfterDiscardingTheOldOne(@TempDir Path workspace) {
        open(workspace).remember(HASH_A);

        BlobCache stale = BlobCache.open(workspace, config("pack"),
                Clock.fixed(NOW.plus(Duration.ofDays(31)), ZoneOffset.UTC));
        stale.remember(HASH_B);

        BlobCache reopened = open(workspace);
        assertFalse(reopened.known(HASH_A));
        assertTrue(reopened.known(HASH_B));
    }
}

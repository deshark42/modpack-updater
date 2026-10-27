package io.github.deshark42.modpackupdater.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.json.Json;
import io.github.deshark42.modpackupdater.log.Text;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@ResourceLock(TestLogs.RESOURCE)
class BlobCacheTest {

    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String HASH_A = Sha256.ofBytes(TestFiles.bytes("cached blob a"));
    private static final String HASH_B = Sha256.ofBytes(TestFiles.bytes("cached blob b"));
    private static final String HASH_C = Sha256.ofBytes(TestFiles.bytes("cached blob c"));

    private static Config config(String bucket) {
        return new Config("https://s3.example.com", null, "us-east-1", bucket, "gtnh/", true, null, null,
                Config.DEFAULT_CONCURRENCY);
    }

    private BlobCache open(Path workspace) {
        return BlobCache.open(workspace, config("pack"), Duration.ofDays(30), CLOCK);
    }

    private BlobCache open(Path workspace, Config config, Duration maxAge, Clock clock) {
        return BlobCache.open(workspace, config, maxAge, clock);
    }

    private static String label(Path workspace) {
        return Text.relativeTo(workspace, Layout.knownBlobsFile(workspace));
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
    void appendingABlobKeepsExistingEntriesInOrder(@TempDir Path workspace) throws IOException {
        BlobCache cache = open(workspace);
        cache.remember(HASH_A);

        cache.remember(HASH_B);

        List<String> lines = Files.readAllLines(Layout.knownBlobsFile(workspace), StandardCharsets.UTF_8);
        assertEquals(3, lines.size());

        Map<String, Object> header = Json.asObject(Json.parse(lines.get(0)), "header");
        assertEquals(BlobCache.fingerprint(config("pack")), header.get("fingerprint"));
        assertEquals(NOW.getEpochSecond(), header.get("createdAt"));
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

        assertEquals(List.of(Map.of("hash", HASH_A)), entries(workspace));
    }

    @Test
    void aCacheAtTheMaximumAgeRemainsUsable(@TempDir Path workspace) {
        open(workspace).remember(HASH_A);
        Clock atTheLimit = Clock.fixed(NOW.plus(Duration.ofDays(30)), ZoneOffset.UTC);

        BlobCache reopened = open(workspace, config("pack"), Duration.ofDays(30), atTheLimit);

        assertTrue(reopened.known(HASH_A));
    }

    @Test
    void aCachePastTheMaximumAgeIsDiscardedWithoutChangingTheFile(@TempDir Path workspace) throws IOException {
        open(workspace).remember(HASH_A);
        byte[] original = Files.readAllBytes(Layout.knownBlobsFile(workspace));
        Clock pastTheLimit = Clock.fixed(NOW.plus(Duration.ofDays(30)).plusSeconds(1), ZoneOffset.UTC);

        BlobCache reopened = open(workspace, config("pack"), Duration.ofDays(30), pastTheLimit);

        assertFalse(reopened.known(HASH_A));
        assertArrayEquals(original, Files.readAllBytes(Layout.knownBlobsFile(workspace)));
    }

    @ParameterizedTest(name = "cache identity changes to {0} / {1} / {2}")
    @CsvSource({"https://other.example.com,pack,gtnh/", "https://s3.example.com,other,gtnh/",
            "https://s3.example.com,pack,other/"})
    void aDifferentBucketLocationDiscardsTheCacheWithoutChangingItsFile(String endpoint, String bucket,
            String prefix, @TempDir Path workspace) throws IOException {
        open(workspace).remember(HASH_A);
        byte[] original = Files.readAllBytes(Layout.knownBlobsFile(workspace));
        Config different = new Config(endpoint, null, "us-east-1", bucket, prefix, true, null, null,
                Config.DEFAULT_CONCURRENCY);

        BlobCache reopened = open(workspace, different, Duration.ofDays(30), CLOCK);

        assertFalse(reopened.known(HASH_A));
        assertArrayEquals(original, Files.readAllBytes(Layout.knownBlobsFile(workspace)));
    }

    @Test
    void aCorruptCacheIsDiscardedWithoutChangingItsFile(@TempDir Path workspace) throws IOException {
        open(workspace).remember(HASH_A);
        Files.writeString(Layout.knownBlobsFile(workspace), "not a cache\n", StandardCharsets.UTF_8);

        BlobCache corrupted = open(workspace);
        assertFalse(corrupted.known(HASH_A));
        assertEquals("not a cache\n", Files.readString(Layout.knownBlobsFile(workspace), StandardCharsets.UTF_8));
    }

    @Test
    void dropsACacheWithAMalformedEntry(@TempDir Path workspace) throws IOException {
        open(workspace).remember(HASH_A);
        Files.writeString(Layout.knownBlobsFile(workspace), Json.write(Map.of("hash", "not-a-hash")),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        assertFalse(open(workspace).known(HASH_A));
    }

    @Test
    void startsANewCacheAfterDiscardingTheOldOne(@TempDir Path workspace) {
        open(workspace).remember(HASH_A);

        BlobCache stale = open(workspace, config("pack"), Duration.ofDays(30),
                Clock.fixed(NOW.plus(Duration.ofDays(31)), ZoneOffset.UTC));
        stale.remember(HASH_B);

        BlobCache reopened = open(workspace);
        assertFalse(reopened.known(HASH_A));
        assertTrue(reopened.known(HASH_B));
    }

    @Test
    void doesNotAppendUnderAnotherBucketLocationsHeader(@TempDir Path workspace) {
        // This instance opened before another location rewrote the file; appending blindly would
        // leave its hash under that other location's header.
        BlobCache first = open(workspace);
        first.remember(HASH_A);

        BlobCache other = open(workspace, config("other"), Duration.ofDays(30), CLOCK);
        other.remember(HASH_B);

        first.remember(HASH_C);

        assertTrue(open(workspace).known(HASH_C));
        assertFalse(open(workspace, config("other"), Duration.ofDays(30), CLOCK).known(HASH_B));
    }

    @Test
    void aCacheWriteFailureKeepsTheRunGoingWithoutInventingAnEntry(@TempDir Path workspace) throws IOException {
        Files.createDirectories(Layout.knownBlobsFile(workspace));
        BlobCache cache = open(workspace);
        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace);

            cache.remember(HASH_A);

            assertFalse(cache.known(HASH_A));
            assertTrue(logs.errText().contains("cannot write the blob cache " + label(workspace)), logs.errText());
            try (var entries = Files.list(Layout.knownBlobsFile(workspace))) {
                assertEquals(List.of(), entries.toList(), "the obstructing directory is not changed");
            }
        }
    }

    @Test
    void aCacheWriteFailureWarnsOnlyOnce(@TempDir Path workspace) throws IOException {
        Files.createDirectories(Layout.knownBlobsFile(workspace));
        BlobCache cache = open(workspace);
        cache.remember(HASH_A);
        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace);

            cache.remember(HASH_B);

            assertEquals("", logs.errText());
            assertEquals("", logs.outText());
            try (var entries = Files.list(Layout.knownBlobsFile(workspace))) {
                assertEquals(List.of(), entries.toList());
            }
        }
    }
}

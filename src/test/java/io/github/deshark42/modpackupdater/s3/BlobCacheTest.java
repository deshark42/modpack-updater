package io.github.deshark42.modpackupdater.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.LogSink;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.json.Json;
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

class BlobCacheTest {

    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String HASH_A = Sha256.ofBytes(TestFiles.bytes("cached blob a"));
    private static final String HASH_B = Sha256.ofBytes(TestFiles.bytes("cached blob b"));
    private static final String HASH_C = Sha256.ofBytes(TestFiles.bytes("cached blob c"));

    private final LogSink sink = new LogSink();

    private static Config config(String bucket) {
        return new Config("https://s3.example.com", "us-east-1", bucket, "gtnh/", true, null, null,
                Config.DEFAULT_CONCURRENCY);
    }

    private BlobCache open(Path workspace) {
        return BlobCache.open(workspace, config("pack"), sink.log(), Duration.ofDays(30), CLOCK);
    }

    private BlobCache open(Path workspace, Config config, Duration maxAge, Clock clock) {
        return BlobCache.open(workspace, config, sink.log(), maxAge, clock);
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

        assertEquals(1L, entries(workspace).stream()
                .filter(entry -> HASH_A.equals(entry.get("hash")))
                .count());
    }

    @Test
    void usesTheMaximumAgeItIsGiven(@TempDir Path workspace) {
        Duration maxAge = Duration.ofDays(30);
        open(workspace).remember(HASH_A);

        Clock atTheLimit = Clock.fixed(NOW.plus(maxAge), ZoneOffset.UTC);
        Clock justPastIt = Clock.fixed(NOW.plus(maxAge).plusSeconds(1), ZoneOffset.UTC);

        assertTrue(open(workspace, config("pack"), maxAge, atTheLimit).known(HASH_A));
        assertFalse(open(workspace, config("pack"), maxAge, justPastIt).known(HASH_A));
    }

    @Test
    void dropsTheCacheOfAnotherBucketLocation(@TempDir Path workspace) {
        open(workspace).remember(HASH_A);

        assertFalse(open(workspace, config("other"), Duration.ofDays(30), CLOCK).known(HASH_A));
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
    void keepsTheRunGoingWhenTheCacheCannotBeWritten(@TempDir Path workspace) throws IOException {
        Files.createDirectories(Layout.knownBlobsFile(workspace));
        BlobCache cache = open(workspace);

        cache.remember(HASH_A);

        assertFalse(cache.known(HASH_A));
        assertTrue(sink.outText().contains("cannot write"), sink.outText());
        assertTrue(sink.outText().contains(Layout.KNOWN_BLOBS), sink.outText());
    }

    @Test
    void warnsAboutAnUnwritableCacheOnlyOnce(@TempDir Path workspace) throws IOException {
        Files.createDirectories(Layout.knownBlobsFile(workspace));
        BlobCache cache = open(workspace);

        cache.remember(HASH_A);
        cache.remember(HASH_B);

        String printed = sink.outText();
        assertEquals(1, printed.split("cannot write", -1).length - 1, printed);
    }
}

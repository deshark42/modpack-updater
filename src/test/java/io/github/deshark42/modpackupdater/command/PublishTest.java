package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.json.Json;
import io.github.deshark42.modpackupdater.log.Logging;
import io.github.deshark42.modpackupdater.model.ChunkTable;
import io.github.deshark42.modpackupdater.model.Chunking;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.FileEntry;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import io.github.deshark42.modpackupdater.model.Pointer;
import io.github.deshark42.modpackupdater.s3.S3Store;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishTest {

    private static final String TAG = "client";

    private TestLogs captured = new TestLogs();

    private void resetLog() {
        captured.close();
        captured = new TestLogs();
    }

    private void publish(Path workspace, String tag, String releaseId) {
        publish(workspace, tag, releaseId, Publish.DEFAULT_CACHE_MAX_AGE_DAYS);
    }

    private void publish(Path workspace, String tag, String releaseId, int cacheMaxAgeDays) {
        publish(workspace, tag, releaseId, cacheMaxAgeDays, Logging.Mode.VERBOSE);
    }

    private void publish(Path workspace, String tag, String releaseId, int cacheMaxAgeDays, Logging.Mode mode) {
        resetLog();
        captured.begin(workspace, "publish-" + tag, mode);
        try {
            Publish.run(workspace, tag, releaseId, cacheMaxAgeDays);
        } finally {
            captured.close();
        }
    }

    /** The message body of a console line: what follows the timestamp and the level. */
    private static String body(String line) {
        int first = line.indexOf(' ');
        int second = first < 0 ? -1 : line.indexOf(' ', first + 1);
        return second < 0 ? line : line.substring(second + 1);
    }


    /** Where the stage heading of this name appears, or -1. */
    private static int stageOf(List<String> lines, String stage) {
        for (int i = 0; i < lines.size(); i++) {
            if (body(lines.get(i)).equals(stage)) {
                return i;
            }
        }
        return -1;
    }

    /** The chunk table a one-chunk file produces, as the publisher will record it. */
    private static ChunkTable tableOf(String content) {
        byte[] bytes = TestFiles.bytes(content);
        return new ChunkTable(Chunking.DEFAULT, bytes.length, List.of(Sha256.ofBytes(bytes)));
    }

    private static String chunkOf(String content) {
        return Sha256.ofBytes(TestFiles.bytes(content));
    }

    private static String releaseKey(String releaseId) {
        return TestWorkspace.bucketPath(ObjectKeys.release(TAG, releaseId));
    }

    private static String releasePut(String releaseId) {
        return "PUT " + releaseKey(releaseId);
    }

    private static String latestKey() {
        return TestWorkspace.bucketPath(ObjectKeys.latest(TAG));
    }

    private static String latestPut() {
        return "PUT " + latestKey();
    }

    private static String chunkKey(String hash) {
        return TestWorkspace.bucketPath(ObjectKeys.blob(hash));
    }

    private static String indexKey(String id) {
        return TestWorkspace.bucketPath(ObjectKeys.index(id));
    }

    private static String blobsPrefix() {
        return TestWorkspace.bucketPath(ObjectKeys.BLOB + "/");
    }

    private static String indexesPrefix() {
        return TestWorkspace.bucketPath(ObjectKeys.INDEX + "/");
    }

    private static String cache(Path workspace) throws IOException {
        Path file = Layout.knownBlobsFile(workspace);
        return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }

    /** A store pointed at the fake, the way a subscriber would open the same bucket. */
    private static S3Store store(FakeS3 s3) {
        return new S3Store(new Config(s3.url(), TestWorkspace.REGION, TestWorkspace.BUCKET,
                TestWorkspace.PREFIX, true, TestWorkspace.ACCESS_KEY, TestWorkspace.SECRET_KEY,
                Config.DEFAULT_CONCURRENCY));
    }

    private static List<Path> logs(Path workspace) throws IOException {
        try (var files = Files.list(Layout.logsDir(workspace))) {
            return files.toList();
        }
    }

    /**
     * Puts the blob cache's creation time at the epoch, so a run's maximum age alone decides whether
     * the cache is trusted and no test reads the system clock.
     */
    private static void ageCache(Path workspace) throws IOException {
        Path file = Layout.knownBlobsFile(workspace);
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        Map<String, Object> header = Json.asObject(Json.parse(lines.get(0)), "cache header");
        header.put("createdAt", 0L);
        lines.set(0, Json.write(header).strip());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    @Test
    void uploadsEveryDistinctChunkAndTableAndPointsLatestAtTheRelease(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");
            TestWorkspace.base(workspace, TAG, "mods/b.jar", "A");
            TestWorkspace.base(workspace, TAG, "mmc-pack.json", "{}");

            publish(workspace, TAG, "2.9.0");

            String chunk = chunkOf("A");
            String table = tableOf("A").indexId();
            List<String> requests = s3.requests();
            String chunkPut = "PUT " + chunkKey(chunk);
            String tablePut = "PUT " + indexKey(table);
            assertEquals(1, requests.stream().filter(request -> request.equals(chunkPut)).count(),
                    "two files with the same content are one chunk: " + requests);
            assertEquals(1, requests.stream().filter(request -> request.equals(tablePut)).count(),
                    "two files with the same content share one chunk table: " + requests);
            assertTrue(requests.indexOf(chunkPut) < requests.indexOf(tablePut), "chunks go before chunk tables");
            assertTrue(requests.indexOf(tablePut) < requests.indexOf(releasePut("2.9.0")),
                    "chunk tables go before the manifest");
            assertTrue(requests.indexOf(releasePut("2.9.0")) < requests.indexOf(latestPut()),
                    "the manifest goes before the latest pointer");
            assertEquals(6, s3.objectCount(), "2 chunks, 2 chunk tables, the manifest and the pointer");

            String release = s3.text(releaseKey("2.9.0"));
            assertEquals("2", Json.asObject(Json.parse(release), "release").get("version").toString());
            Manifest manifest = Manifest.parse(release, "release");
            assertEquals(List.of("mmc-pack.json", "mods/a.jar", "mods/b.jar"),
                    List.copyOf(manifest.files().keySet()));
            assertEquals(chunk, manifest.files().get("mods/a.jar").hash());
            assertEquals(table, manifest.files().get("mods/a.jar").indexId());
            assertEquals(table, manifest.files().get("mods/b.jar").indexId());
            ChunkTable stored = ChunkTable.parse(s3.text(indexKey(table)), "chunk table");
            assertEquals(List.of(chunk), stored.chunks());
            assertEquals(1, stored.size());
            assertEquals(Chunking.DEFAULT, stored.chunking());
            assertEquals("2.9.0", Pointer.parse(s3.text(latestKey()), "latest").releaseId());
            assertTrue(captured.outText().contains("3 files"), captured.outText());
        }
    }

    @Test
    void gzipsTheReleaseManifestAndLeavesTheLatestPointerPlain(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");

            publish(workspace, TAG, "2.9.0");

            byte[] stored = s3.object(releaseKey("2.9.0"));
            assertEquals(0x1f, stored[0] & 0xff, "the release manifest is stored as a gzip member");
            assertEquals(0x8b, stored[1] & 0xff);
            assertEquals("gzip", s3.contentEncoding(releaseKey("2.9.0")));
            assertNull(s3.contentEncoding(latestKey()), "the latest pointer stays plain");
            Manifest read = Manifest.parse(new String(
                    store(s3).getBytes(ObjectKeys.release(TAG, "2.9.0")), StandardCharsets.UTF_8), "release");
            assertEquals(List.of("mods/a.jar"), List.copyOf(read.files().keySet()));
            assertTrue(captured.outText().contains(" -> "), "the write line reports both sizes: " + captured.outText());
        }
    }

    @Test
    void refusesToRewriteAPublishedRelease(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");
            publish(workspace, TAG, "2.9.0");
            int before = s3.objectCount();

            TestWorkspace.base(workspace, TAG, "mods/a.jar", "changed");
            UserError error = assertThrows(UserError.class,
                    () -> publish(workspace, TAG, "2.9.0"));

            assertTrue(error.getMessage().contains("already exists"), error.getMessage());
            assertEquals(before, s3.objectCount());
        }
    }

    @Test
    void reportsATagWithoutABaseDirectory(@TempDir Path workspace) {
        UserError error = assertThrows(UserError.class,
                () -> publish(workspace, TAG, "2.9.0"));
        assertTrue(error.getMessage().contains("no such directory"), error.getMessage());
    }

    @Test
    void storesEachDistinctChunkOnceAcrossReleases(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");
            publish(workspace, TAG, "2.9.0");

            TestWorkspace.base(workspace, TAG, "mods/b.jar", "B");
            publish(workspace, TAG, "2.9.1");

            List<String> requests = s3.requests();
            assertEquals(2, requests.stream().filter(request -> request.startsWith("PUT " + blobsPrefix())).count(),
                    "one chunk per distinct content: " + requests);
            assertEquals(2, requests.stream().filter(request -> request.startsWith("PUT " + indexesPrefix())).count(),
                    "one chunk table per distinct content: " + requests);
            assertNotNull(s3.object(chunkKey(chunkOf("A"))));
            assertEquals(7, s3.objectCount(), "2 chunks, 2 chunk tables, 2 manifests and the pointer");
        }
    }

    @Test
    void uploadsOnlyTheChunksAroundTheChange(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            byte[] before = TestFiles.random(300_000, 21);
            byte[] after = before.clone();
            after[150_000] ^= 0x7f;
            TestFiles.write(Layout.baseDir(workspace, TAG), "mods/a.jar", before);
            publish(workspace, TAG, "2.9.0");
            long uploaded = s3.uploadedBytes();

            TestFiles.write(Layout.baseDir(workspace, TAG), "mods/a.jar", after);
            publish(workspace, TAG, "2.9.1");
            long delta = s3.uploadedBytes() - uploaded;

            assertTrue(delta > 0, "something was uploaded");
            assertTrue(delta < before.length / 2,
                    "only the chunks around the change are uploaded: " + delta + " of " + before.length);
            ChunkTable first = ChunkTable.parse(s3.text(indexKey(
                    Manifest.parse(s3.text(releaseKey("2.9.0")), "release").files().get("mods/a.jar").indexId())),
                    "chunk table");
            ChunkTable second = ChunkTable.parse(s3.text(indexKey(
                    Manifest.parse(s3.text(releaseKey("2.9.1")), "release").files().get("mods/a.jar").indexId())),
                    "chunk table");
            Set<String> shared = new HashSet<>(first.chunks());
            shared.retainAll(second.chunks());
            assertTrue(shared.size() >= first.chunks().size() - 2,
                    "the two versions share almost every chunk: " + shared.size() + " of " + first.chunks().size());
            assertFalse(first.indexId().equals(second.indexId()), "the changed file has its own chunk table");
        }
    }

    @Test
    void sharesChunksAndTablesBetweenTags(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");
            TestWorkspace.base(workspace, "client-extra", "config/a.cfg", "A");
            publish(workspace, TAG, "1.0");
            int before = s3.requests().size();
            resetLog();

            publish(workspace, "client-extra", "1.0");

            List<String> second = s3.requests().subList(before, s3.requests().size());
            assertTrue(captured.outText().contains("plan 0 chunks to upload, 1 chunk already there"),
                    captured.outText());
            assertFalse(second.stream().anyMatch(request -> request.startsWith("PUT " + blobsPrefix())),
                    second.toString());
            assertFalse(second.stream().anyMatch(request -> request.startsWith("PUT " + indexesPrefix())),
                    second.toString());
            String client = Manifest.parse(s3.text(releaseKey("1.0")), "release")
                    .files().get("mods/a.jar").indexId();
            String extra = Manifest.parse(s3.text(TestWorkspace.bucketPath(ObjectKeys.release("client-extra", "1.0"))),
                    "release").files().get("config/a.cfg").indexId();
            assertEquals(client, extra, "the same content under two tags is one chunk table");
        }
    }

    @Test
    void keepsTheTranscriptOfARunThatUploaded(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");

            publish(workspace, TAG, "2.9.0");

            List<Path> logs = logs(workspace);
            assertEquals(1, logs.size(), logs.toString());
            assertTrue(logs.get(0).getFileName().toString().contains("publish-client"),
                    logs.get(0).toString());
            String text = Files.readString(logs.get(0), StandardCharsets.UTF_8);
            assertTrue(text.contains("publish client 2.9.0"), text);
            assertTrue(text.contains("upload mods/a.jar"), text);
            assertTrue(text.contains("done: client 2.9.0 is now the latest"), text);
        }
    }

    @Test
    void reportsWhatItUploadedAndWhatItSkipped(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");
            TestWorkspace.base(workspace, TAG, "mods/b.jar", "B");
            publish(workspace, TAG, "2.9.0");
            resetLog();

            TestWorkspace.base(workspace, TAG, "mods/c.jar", "C");
            publish(workspace, TAG, "2.9.1");

            String text = captured.outText();
            assertTrue(text.contains("plan 1 chunk to upload, 2 chunks already there"), text);
            assertTrue(text.contains("upload 1 chunk"), text);
            assertTrue(text.contains("skip 2 chunks: already in the bucket"), text);
        }
    }

    @Test
    void chunksTheOverlayNotTheFileItReplaces(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "base");
            TestWorkspace.base(workspace, TAG, "mods/b.jar", "B");
            TestWorkspace.overlay(workspace, TAG, "mods/a.jar", "overlay");
            TestWorkspace.overlay(workspace, TAG, "config/mine.json", "{}");

            publish(workspace, TAG, "2.9.0");

            Manifest manifest = Manifest.parse(s3.text(releaseKey("2.9.0")), "release");
            assertEquals(List.of("config/mine.json", "mods/a.jar", "mods/b.jar"),
                    List.copyOf(manifest.files().keySet()));
            assertEquals(chunkOf("overlay"), manifest.files().get("mods/a.jar").hash(),
                    "overlay wins the same path");
            assertEquals("overlay", s3.text(chunkKey(chunkOf("overlay"))));
            String text = captured.outText();
            assertTrue(text.contains("replace mods/a.jar"), text);
            assertTrue(text.contains("add config/mine.json"), text);
        }
    }

    @Test
    void removesTheBaseFilesRemoveTxtMatches(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");
            TestWorkspace.base(workspace, TAG, "mods/example-mod-1.0.jar", "B");
            TestWorkspace.base(workspace, TAG, "config/secret.cfg", "C");
            TestWorkspace.base(workspace, TAG, "config/deep/other.cfg", "D");
            TestWorkspace.remove(workspace, TAG, "example-mod-*.jar\n/config/\nghost.jar\n");

            publish(workspace, TAG, "2.9.0");

            Manifest manifest = Manifest.parse(s3.text(releaseKey("2.9.0")), "release");
            assertEquals(List.of("mods/a.jar"), List.copyOf(manifest.files().keySet()));
            String text = captured.outText();
            assertTrue(text.contains("remove 3 files from 3 rules"), text);
            assertTrue(text.contains("stale 1 rule: ghost.jar"), text);
            assertTrue(text.contains("ghost.jar"), text);
            assertTrue(text.contains("merge 0 files added, 0 files replaced"), text);
        }
    }

    @Test
    void removesNothingFromTheOverlay(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/example-mod.jar", "base");
            TestWorkspace.base(workspace, TAG, "mods/other.jar", "B");
            TestWorkspace.overlay(workspace, TAG, "mods/example-mod.jar", "mine");
            TestWorkspace.remove(workspace, TAG, "example-mod.jar\n");

            publish(workspace, TAG, "2.9.0");

            Manifest manifest = Manifest.parse(s3.text(releaseKey("2.9.0")), "release");
            assertEquals(List.of("mods/example-mod.jar", "mods/other.jar"),
                    List.copyOf(manifest.files().keySet()));
            assertEquals(chunkOf("mine"), manifest.files().get("mods/example-mod.jar").hash());
        }
    }

    @Test
    void refusesABadRemoveRuleBeforeTouchingTheBucket(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");
            TestWorkspace.remove(workspace, TAG, "../escape.jar\n");

            UserError error = assertThrows(UserError.class,
                    () -> publish(workspace, TAG, "2.9.0"));

            assertTrue(error.getMessage().contains("leaves the tag directory"), error.getMessage());
            assertTrue(s3.requests().isEmpty(), s3.requests().toString());
        }
    }

    @Test
    void namesEachStageAndEndsWithASummary(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");
            publish(workspace, TAG, "2.9.0");

            List<String> lines = captured.outText().lines().toList();
            List<String> bodies = lines.stream().map(PublishTest::body).toList();
            int inputs = stageOf(lines, "inputs");
            int transfer = stageOf(lines, "transfer");
            int commit = stageOf(lines, "commit");
            int done = -1;
            for (int i = 0; i < bodies.size(); i++) {
                if (bodies.get(i).startsWith("done: ")) {
                    done = i;
                }
            }
            assertTrue(inputs > 0 && transfer > inputs && commit > transfer && done > commit, lines.toString());
            assertEquals("done: client 2.9.0 is now the latest", bodies.get(done), lines.toString());
            for (String stage : List.of("scan ", "merge ", "chunk ", "plan ", "upload ", "write ")) {
                assertTrue(bodies.stream().anyMatch(body -> body.startsWith(stage)),
                        "the " + stage.strip() + " stage reports: " + lines);
            }
            assertTrue(bodies.stream().anyMatch(body -> body.startsWith("chunk 1 file: 1 chunk")), lines.toString());
            assertTrue(bodies.stream().anyMatch(body -> body.startsWith("plan 1 chunk to upload")), lines.toString());
            assertTrue(bodies.get(done + 1).startsWith("contents: "), lines.toString());
            assertTrue(bodies.get(bodies.size() - 1).startsWith("transcript: "), lines.toString());
        }
    }

    @Test
    void asksTheBucketOnlyAboutObjectsTheCacheDoesNotKnow(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");
            publish(workspace, TAG, "2.9.0");
            String chunkA = chunkOf("A");
            String tableA = tableOf("A").indexId();
            int before = s3.requests().size();

            TestWorkspace.base(workspace, TAG, "mods/b.jar", "B");
            publish(workspace, TAG, "2.9.1");
            List<String> second = s3.requests().subList(before, s3.requests().size());

            assertFalse(second.contains("HEAD " + chunkKey(chunkA)), second.toString());
            assertFalse(second.contains("HEAD " + indexKey(tableA)), second.toString());
            assertEquals(1, second.stream().filter(request -> request.equals("HEAD " + chunkKey(chunkOf("B")))).count(),
                    second.toString());
            assertEquals(1, second.stream().filter(request -> request.equals("HEAD " + indexKey(tableOf("B").indexId()))).count(),
                    second.toString());
        }
    }

    @Test
    void remembersObjectsItFoundInTheBucket(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");
            String chunk = chunkOf("A");
            s3.put(chunkKey(chunk), TestFiles.bytes("A"));

            publish(workspace, TAG, "2.9.0");

            assertTrue(cache(workspace).contains(chunk), cache(workspace));
            assertFalse(s3.requests().stream().anyMatch(request -> request.startsWith("PUT " + chunkKey(chunk))),
                    s3.requests().toString());
            assertTrue(captured.outText().contains("skip 1 chunk: already in the bucket"), captured.outText());
        }
    }

    @Test
    void keepsConfirmedChunksWhenALaterStepFails(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");
            String chunk = chunkOf("A");
            s3.put(chunkKey(chunk), TestFiles.bytes("A"));
            s3.failPut(releaseKey("2.9.0"));

            UserError error = assertThrows(UserError.class,
                    () -> publish(workspace, TAG, "2.9.0"));

            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
            assertTrue(cache(workspace).contains(chunk), cache(workspace));
            assertNull(s3.object(releaseKey("2.9.0")), "a failed publish writes no manifest");
            assertNull(s3.object(latestKey()), "a failed publish does not move latest");
            assertFalse(s3.requests().contains(latestPut()), "the pointer is never reached: " + s3.requests());
        }
    }

    @Test
    void keepsUploadedChunksWhenALaterStepFails(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");
            publish(workspace, TAG, "2.9.0");
            String latest = s3.text(latestKey());
            TestWorkspace.base(workspace, TAG, "mods/b.jar", "B");
            String chunk = chunkOf("B");
            s3.failPut(releaseKey("2.9.1"));

            UserError error = assertThrows(UserError.class,
                    () -> publish(workspace, TAG, "2.9.1"));

            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
            assertNotNull(s3.object(chunkKey(chunk)), "the chunks uploaded before the failure stay");
            assertTrue(cache(workspace).contains(chunk), cache(workspace));
            assertNull(s3.object(releaseKey("2.9.1")), "a failed publish writes no manifest");
            assertEquals(latest, s3.text(latestKey()), "latest still points at the release it pointed at");
        }
    }

    @Test
    void doesNotRememberAFailedUpload(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");
            String chunk = chunkOf("A");
            s3.failPut(chunkKey(chunk));

            UserError error = assertThrows(UserError.class,
                    () -> publish(workspace, TAG, "2.9.0"));
            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
            assertFalse(cache(workspace).contains(chunk), cache(workspace));

            s3.clearFailures();
            resetLog();
            publish(workspace, TAG, "2.9.0");
            assertTrue(cache(workspace).contains(chunk), cache(workspace));
            assertNotNull(s3.object(chunkKey(chunk)));
        }
    }

    @Test
    void givesUpTheCacheWhenTheBucketChanges(@TempDir Path workspace) throws IOException {
        try (FakeS3 first = new FakeS3(); FakeS3 second = new FakeS3()) {
            TestWorkspace.configure(workspace, first);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");
            publish(workspace, TAG, "2.9.0");
            String chunk = chunkOf("A");

            TestWorkspace.configure(workspace, second);
            publish(workspace, TAG, "2.9.1");

            assertTrue(second.requests().contains("HEAD " + chunkKey(chunk)),
                    "a cache from another bucket location is discarded: " + second.requests());
        }
    }

    @Test
    void rebuildsACorruptedCache(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");
            publish(workspace, TAG, "2.9.0");
            String chunk = chunkOf("A");
            Files.writeString(Layout.knownBlobsFile(workspace), "not a cache\n", StandardCharsets.UTF_8);
            int before = s3.requests().size();

            publish(workspace, TAG, "2.9.1");
            List<String> second = s3.requests().subList(before, s3.requests().size());

            assertTrue(second.contains("HEAD " + chunkKey(chunk)), second.toString());
            assertTrue(cache(workspace).contains(chunk), cache(workspace));
        }
    }

    @Test
    void remembersEveryObjectUploadedInParallel(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            for (int i = 0; i < 8; i++) {
                TestWorkspace.base(workspace, TAG, "mods/" + i + ".jar", "content-" + i);
            }

            publish(workspace, TAG, "2.9.0");

            String cache = cache(workspace);
            for (int i = 0; i < 8; i++) {
                assertTrue(cache.contains(chunkOf("content-" + i)), cache);
                assertTrue(cache.contains(tableOf("content-" + i).indexId()), cache);
            }
        }
    }

    @Test
    void aFailingReleaseCheckStopsBeforeAnyUpload(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");
            s3.fail("HEAD", releaseKey("2.9.0"), 500);

            UserError error = assertThrows(UserError.class,
                    () -> publish(workspace, TAG, "2.9.0"));

            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
            assertFalse(s3.requests().stream().anyMatch(request -> request.startsWith("PUT ")),
                    "a run that cannot check the release writes nothing: " + s3.requests());
            assertFalse(Files.exists(Layout.knownBlobsFile(workspace)), "no cache is written");
        }
    }

    @Test
    void dropsACacheOlderThanItsMaximumAge(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");
            publish(workspace, TAG, "2.9.0");
            String chunk = chunkOf("A");
            ageCache(workspace);
            int before = s3.requests().size();

            publish(workspace, TAG, "2.9.1", 1);

            List<String> second = s3.requests().subList(before, s3.requests().size());
            assertTrue(second.contains("HEAD " + chunkKey(chunk)),
                    "an expired cache is not trusted, so the bucket is asked again: " + second);
            assertTrue(cache(workspace).contains(chunk), cache(workspace));
        }
    }

    @Test
    void trustsACacheYoungerThanItsMaximumAge(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");
            publish(workspace, TAG, "2.9.0");
            String chunk = chunkOf("A");
            ageCache(workspace);
            int before = s3.requests().size();

            publish(workspace, TAG, "2.9.1", 100_000);

            List<String> second = s3.requests().subList(before, s3.requests().size());
            assertFalse(second.contains("HEAD " + chunkKey(chunk)),
                    "a cache younger than an enormous maximum age answers without a request: " + second);
        }
    }

    @Test
    void uploadsEveryBlobOfAConcurrentPublishExactlyOnce(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            for (int i = 0; i < 12; i++) {
                TestFiles.write(Layout.baseDir(workspace, TAG), "mods/" + i + ".jar",
                        TestFiles.random(150_000, 300 + i));
            }

            publish(workspace, TAG, "2.9.0");

            assertTrue(s3.maxInFlight() > 1, "the run overlaps its requests: " + s3.maxInFlight());
            Set<String> puts = new HashSet<>();
            for (String request : s3.requests()) {
                if (request.startsWith("PUT ")) {
                    assertTrue(puts.add(request), "one PUT per object: " + request);
                }
            }
            Manifest manifest = Manifest.parse(s3.text(releaseKey("2.9.0")), "release");
            assertEquals(12, manifest.files().size());
            for (Map.Entry<String, FileEntry> entry : manifest.files().entrySet()) {
                ChunkTable table = ChunkTable.parse(s3.text(indexKey(entry.getValue().indexId())),
                        "chunk table");
                assertEquals(entry.getValue().size(), table.size());
                for (String hash : table.chunks()) {
                    byte[] blob = s3.object(chunkKey(hash));
                    assertNotNull(blob, "every chunk of " + entry.getKey() + " was uploaded");
                    assertEquals(hash, Sha256.ofBytes(blob), "a blob is stored under the hash of its content");
                }
            }
            assertEquals(puts.size(), s3.objectCount(), "one object per PUT");
            assertEquals("2.9.0", Pointer.parse(s3.text(latestKey()), "latest").releaseId());
        }
    }
}

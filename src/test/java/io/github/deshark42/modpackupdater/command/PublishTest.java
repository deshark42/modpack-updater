package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.log.Log;
import io.github.deshark42.modpackupdater.model.ChunkTable;
import io.github.deshark42.modpackupdater.model.Chunking;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishTest {

    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(printed, true, StandardCharsets.UTF_8);
    private final Log log = new Log(out, Log.Level.VERBOSE);

    private static void write(Path root, String name, String content) throws IOException {
        write(root, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Path root, String name, byte[] content) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }

    private static void configure(Path workspace, FakeS3 s3) throws IOException {
        write(workspace, ".modpack-updater/config.json", "{\"endpoint\": \"" + s3.url()
                + "\", \"bucket\": \"pack\", \"prefix\": \"gtnh\", \"path_style\": true,"
                + " \"access_key\": \"AK\", \"secret_key\": \"SK\"}");
    }

    private static int indexOf(List<String> requests, String prefix) {
        for (int i = 0; i < requests.size(); i++) {
            if (requests.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    /** The chunk table a one-chunk file produces, as the publisher will record it. */
    private static ChunkTable tableOf(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new ChunkTable(Chunking.DEFAULT, bytes.length, List.of(Sha256.ofBytes(bytes)));
    }

    private static String chunkOf(String content) {
        return Sha256.ofBytes(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void uploadsEveryDistinctChunkAndTableAndPointsLatestAtTheRelease(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            write(workspace, "client/base/mods/b.jar", "A");
            write(workspace, "client/base/mmc-pack.json", "{}");

            Publish.run(workspace, "client", "2.9.0", false, log);

            String chunkA = chunkOf("A");
            String tableA = tableOf("A").indexId();
            List<String> requests = s3.requests();
            String chunkPut = "PUT " + chunkKey(chunkA);
            String tablePut = "PUT " + indexKey(tableA);
            assertTrue(requests.contains(chunkPut), requests.toString());
            assertEquals(1, requests.stream().filter(r -> r.equals(chunkPut)).count(),
                    "two files with the same content are one chunk");
            assertEquals(1, requests.stream().filter(r -> r.equals(tablePut)).count(),
                    "two files with the same content share one chunk table");
            assertTrue(indexOf(requests, chunkPut) < indexOf(requests, tablePut), "chunks go before chunk tables");
            assertTrue(indexOf(requests, tablePut) < indexOf(requests, releasePut("2.9.0")),
                    "chunk tables go before the manifest");
            assertTrue(indexOf(requests, releasePut("2.9.0")) < indexOf(requests, latestPut()),
                    "the manifest goes before the latest pointer");
            assertEquals(6, s3.objectCount(), "2 chunks, 2 chunk tables, the manifest and the pointer");

            String release = s3.text(releasePath("2.9.0"));
            assertTrue(release.contains("\"version\": 2"), release);
            Manifest manifest = Manifest.parse(release, "release");
            assertEquals(List.of("mmc-pack.json", "mods/a.jar", "mods/b.jar"),
                    List.copyOf(manifest.files().keySet()));
            assertEquals(Sha256.ofBytes("A".getBytes(StandardCharsets.UTF_8)), manifest.files().get("mods/a.jar").hash());
            assertEquals(tableA, manifest.files().get("mods/a.jar").indexId());
            assertEquals(tableA, manifest.files().get("mods/b.jar").indexId());
            ChunkTable stored = ChunkTable.parse(s3.text(indexKey(tableA)), "chunk table");
            assertEquals(List.of(chunkA), stored.chunks());
            assertEquals(1, stored.size());
            assertEquals(Chunking.DEFAULT, stored.chunking());
            assertTrue(s3.text(latestPath()).contains("2.9.0"));
            assertTrue(printed.toString().contains("3 files"), printed.toString());
        }
    }

    @Test
    void reportsUploadProgressWhileItUploads(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            write(workspace, "client/base/mods/b.jar", "B");
            write(workspace, "client/base/mods/c.jar", "C");

            ByteArrayOutputStream bar = new ByteArrayOutputStream();
            AtomicLong clock = new AtomicLong();
            Log ticking = new Log(out, new PrintStream(bar, true, StandardCharsets.UTF_8), Log.Level.NORMAL,
                    false, true, () -> clock.addAndGet(2_000_000_000L));
            Publish.run(workspace, "client", "2.9.0", false, ticking);

            String frames = bar.toString();
            assertTrue(frames.contains("2/3 chunks"), frames);
            assertTrue(frames.endsWith("\r\u001b[K"), "the bar is erased when the transfer ends: " + frames);
            assertFalse(frames.contains("3/3 chunks"), "the last chunk does not draw a bar: " + frames);
        }
    }

    @Test
    void refusesToRewriteAPublishedRelease(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            Publish.run(workspace, "client", "2.9.0", false, log);
            int before = s3.objectCount();

            write(workspace, "client/base/mods/a.jar", "changed");
            UserError error = assertThrows(UserError.class, () -> Publish.run(workspace, "client", "2.9.0", false, log));

            assertTrue(error.getMessage().contains("already exists"), error.getMessage());
            assertEquals(before, s3.objectCount());
        }
    }

    @Test
    void dryRunUploadsNothingAndNeedsNoConnection(@TempDir Path workspace) throws IOException {
        write(workspace, "client/base/mods/a.jar", "A");

        Publish.run(workspace, "client", "2.9.0", true, log);

        assertTrue(printed.toString().contains("dry run"), printed.toString());
        assertTrue(printed.toString().contains("1 file,"), printed.toString());
        assertFalse(Files.exists(Layout.knownBlobsFile(workspace)), "a dry run writes no cache");
    }

    @Test
    void reportsATagWithoutABaseDirectory(@TempDir Path workspace) {
        UserError error = assertThrows(UserError.class, () -> Publish.run(workspace, "client", "2.9.0", false, log));
        assertTrue(error.getMessage().contains("no such directory"), error.getMessage());
    }

    @Test
    void storesEachDistinctChunkOnceAcrossReleases(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            Publish.run(workspace, "client", "2.9.0", false, log);

            write(workspace, "client/base/mods/b.jar", "B");
            Publish.run(workspace, "client", "2.9.1", false, log);

            List<String> requests = s3.requests();
            assertEquals(2, requests.stream().filter(r -> r.startsWith("PUT /pack/gtnh/blob/")).count(),
                    "one chunk per distinct content: " + requests);
            assertEquals(2, requests.stream().filter(r -> r.startsWith("PUT /pack/gtnh/index/")).count(),
                    "one chunk table per distinct content: " + requests);
            assertNotNull(s3.object(chunkKey(chunkOf("A"))));
            assertEquals(7, s3.objectCount(), "2 chunks, 2 chunk tables, 2 manifests and the pointer");
        }
    }

    @Test
    void uploadsOnlyTheChunksAroundTheChange(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            byte[] before = random(300_000, 21);
            byte[] after = before.clone();
            after[150_000] ^= 0x7f;
            write(workspace, "client/base/mods/a.jar", before);
            Publish.run(workspace, "client", "2.9.0", false, log);
            long uploaded = s3.uploadedBytes();

            write(workspace, "client/base/mods/a.jar", after);
            Publish.run(workspace, "client", "2.9.1", false, log);
            long delta = s3.uploadedBytes() - uploaded;

            assertTrue(delta > 0, "something was uploaded");
            assertTrue(delta < before.length / 2,
                    "only the chunks around the change are uploaded: " + delta + " of " + before.length);
            ChunkTable first = ChunkTable.parse(s3.text(indexKey(
                    Manifest.parse(s3.text(releasePath("2.9.0")), "release").files().get("mods/a.jar").indexId())),
                    "chunk table");
            ChunkTable second = ChunkTable.parse(s3.text(indexKey(
                    Manifest.parse(s3.text(releasePath("2.9.1")), "release").files().get("mods/a.jar").indexId())),
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
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            write(workspace, "client-extra/base/config/a.cfg", "A");
            Publish.run(workspace, "client", "1.0", false, log);
            int before = s3.requests().size();
            printed.reset();

            Publish.run(workspace, "client-extra", "1.0", false, log);

            List<String> second = s3.requests().subList(before, s3.requests().size());
            assertTrue(printed.toString().contains("plan     0 chunks to upload, 1 chunk already there"),
                    printed.toString());
            assertFalse(second.stream().anyMatch(request -> request.startsWith("PUT /pack/gtnh/blob/")),
                    second.toString());
            assertFalse(second.stream().anyMatch(request -> request.startsWith("PUT /pack/gtnh/index/")),
                    second.toString());
            String client = Manifest.parse(s3.text(releasePath("1.0")), "release")
                    .files().get("mods/a.jar").indexId();
            String extra = Manifest.parse(s3.text("/pack/gtnh/metadata/client-extra/releases/1.0.json"), "release")
                    .files().get("config/a.cfg").indexId();
            assertEquals(client, extra, "the same content under two tags is one chunk table");
        }
    }

    @Test
    void keepsTheTranscriptOfARunThatUploaded(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");

            Publish.run(workspace, "client", "2.9.0", false, log);

            List<Path> logs = logs(workspace);
            assertEquals(1, logs.size(), logs.toString());
            assertTrue(logs.get(0).getFileName().toString().endsWith("-publish-client.log"),
                    logs.get(0).toString());
            String text = Files.readString(logs.get(0));
            assertTrue(text.contains("publish client 2.9.0"), text);
            assertTrue(text.contains("upload   mods/a.jar"), text);
            assertTrue(text.contains("done: client 2.9.0 is now the latest"), text);
        }
    }

    @Test
    void reportsWhatItUploadedAndWhatItSkipped(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            write(workspace, "client/base/mods/b.jar", "B");
            Publish.run(workspace, "client", "2.9.0", false, log);
            printed.reset();

            write(workspace, "client/base/mods/c.jar", "C");
            Publish.run(workspace, "client", "2.9.1", false, log);

            String text = printed.toString();
            assertTrue(text.contains("plan     1 chunk to upload, 2 chunks already there"), text);
            assertTrue(text.contains("upload   1 chunk"), text);
            assertTrue(text.contains("skip     2 chunks"), text);
        }
    }

    @Test
    void writesNoTranscriptForADryRun(@TempDir Path workspace) throws IOException {
        write(workspace, "client/base/mods/a.jar", "A");

        Publish.run(workspace, "client", "2.9.0", true, log);

        assertFalse(Files.exists(Layout.logsDir(workspace)));
    }

    @Test
    void chunksTheOverlayNotTheFileItReplaces(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "base");
            write(workspace, "client/base/mods/b.jar", "B");
            write(workspace, "client/overlay/mods/a.jar", "overlay");
            write(workspace, "client/overlay/config/mine.json", "{}");

            Publish.run(workspace, "client", "2.9.0", false, log);

            Manifest manifest = Manifest.parse(s3.text(releasePath("2.9.0")), "release");
            assertEquals(List.of("config/mine.json", "mods/a.jar", "mods/b.jar"),
                    List.copyOf(manifest.files().keySet()));
            assertEquals(Sha256.ofBytes("overlay".getBytes(StandardCharsets.UTF_8)),
                    manifest.files().get("mods/a.jar").hash(), "overlay wins the same path");
            assertEquals("overlay", new String(s3.object(chunkKey(chunkOf("overlay"))), StandardCharsets.UTF_8));
            String text = printed.toString();
            assertTrue(text.contains("replace  mods/a.jar"), text);
            assertTrue(text.contains("add      config/mine.json"), text);
        }
    }

    @Test
    void removesTheBaseFilesRemoveTxtMatches(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            write(workspace, "client/base/mods/example-mod-1.0.jar", "B");
            write(workspace, "client/base/config/secret.cfg", "C");
            write(workspace, "client/base/config/deep/other.cfg", "D");
            Files.writeString(Layout.removeFile(workspace, "client"),
                    "example-mod-*.jar\n/config/\nghost.jar\n");

            Publish.run(workspace, "client", "2.9.0", false, log);

            Manifest manifest = Manifest.parse(s3.text(releasePath("2.9.0")), "release");
            assertEquals(List.of("mods/a.jar"), List.copyOf(manifest.files().keySet()));
            String text = printed.toString();
            assertTrue(text.contains("remove   3 files"), text);
            assertTrue(text.contains("stale    1 rule"), text);
            assertTrue(text.contains("ghost.jar"), text);
            assertTrue(text.contains("merge    0 files added, 0 files replaced"), text);
        }
    }

    @Test
    void removesNothingFromTheOverlay(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/example-mod.jar", "base");
            write(workspace, "client/base/mods/other.jar", "B");
            write(workspace, "client/overlay/mods/example-mod.jar", "mine");
            Files.writeString(Layout.removeFile(workspace, "client"), "example-mod.jar\n");

            Publish.run(workspace, "client", "2.9.0", false, log);

            Manifest manifest = Manifest.parse(s3.text(releasePath("2.9.0")), "release");
            assertEquals(List.of("mods/example-mod.jar", "mods/other.jar"),
                    List.copyOf(manifest.files().keySet()));
            assertEquals(Sha256.ofBytes("mine".getBytes(StandardCharsets.UTF_8)),
                    manifest.files().get("mods/example-mod.jar").hash());
        }
    }

    @Test
    void dryRunReportsTheSameMergeAsTheRealPublish(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            write(workspace, "client/base/mods/a.jar", "base");
            write(workspace, "client/overlay/mods/a.jar", "overlay");
            write(workspace, "client/overlay/config/mine.json", "{}");
            Files.writeString(Layout.removeFile(workspace, "client"), "ghost.jar\n");

            Log quiet = new Log(out, Log.Level.NORMAL);
            Publish.run(workspace, "client", "2.9.0", true, quiet);
            String dry = line(printed.toString(), "merge");
            printed.reset();

            configure(workspace, s3);
            Publish.run(workspace, "client", "2.9.0", false, quiet);
            String real = line(printed.toString(), "merge");

            assertNotNull(dry, "a dry run reports the merge");
            assertEquals(dry, real);
        }
    }

    @Test
    void refusesABadRemoveRuleBeforeTouchingTheBucket(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            Files.writeString(Layout.removeFile(workspace, "client"), "../escape.jar\n");

            UserError error = assertThrows(UserError.class,
                    () -> Publish.run(workspace, "client", "2.9.0", false, log));

            assertTrue(error.getMessage().contains("leaves the tag directory"), error.getMessage());
            assertTrue(s3.requests().isEmpty(), s3.requests().toString());
        }
    }

    @Test
    void namesEachStageAndEndsWithASummary(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            Publish.run(workspace, "client", "2.9.0", false, log);

            List<String> lines = printed.toString().lines().toList();
            int inputs = lines.indexOf("inputs");
            int transfer = lines.indexOf("transfer");
            int commit = lines.indexOf("commit");
            int done = lines.indexOf(lines.stream().filter(line -> line.startsWith("done: "))
                    .findFirst().orElseThrow());
            assertTrue(inputs > 0 && transfer > inputs && commit > transfer && done > commit, lines.toString());
            assertTrue(lines.get(inputs - 1).isEmpty(), "a stage is set off by a blank line: " + lines);
            assertTrue(lines.get(done + 1).startsWith("  contents  "), lines.toString());
            assertTrue(lines.get(lines.size() - 1).startsWith("  log       "), lines.toString());
            assertTrue(lines.stream().anyMatch(line -> line.startsWith("  chunk    ")), lines.toString());
            assertTrue(lines.stream().anyMatch(line -> line.startsWith("  upload   ")), lines.toString());
        }
    }

    @Test
    void asksTheBucketOnlyAboutObjectsTheCacheDoesNotKnow(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            Publish.run(workspace, "client", "2.9.0", false, log);
            String chunkA = chunkOf("A");
            String tableA = tableOf("A").indexId();
            int before = s3.requests().size();

            write(workspace, "client/base/mods/b.jar", "B");
            Publish.run(workspace, "client", "2.9.1", false, log);
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
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            String chunk = chunkOf("A");
            s3.put(chunkKey(chunk), "A".getBytes(StandardCharsets.UTF_8));

            Publish.run(workspace, "client", "2.9.0", false, log);

            assertTrue(cache(workspace).contains(chunk), cache(workspace));
            assertFalse(s3.requests().stream().anyMatch(request -> request.startsWith("PUT " + chunkKey(chunk))),
                    s3.requests().toString());
            assertTrue(printed.toString().contains("skip     1 chunk"), printed.toString());
        }
    }

    @Test
    void keepsConfirmedChunksWhenALaterStepFails(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            String chunk = chunkOf("A");
            s3.put(chunkKey(chunk), "A".getBytes(StandardCharsets.UTF_8));
            s3.failPut(releasePath("2.9.0"));

            assertThrows(UserError.class, () -> Publish.run(workspace, "client", "2.9.0", false, log));

            assertTrue(cache(workspace).contains(chunk), cache(workspace));
        }
    }

    @Test
    void keepsUploadedChunksWhenALaterStepFails(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            String chunk = chunkOf("A");
            s3.failPut(releasePath("2.9.0"));

            assertThrows(UserError.class, () -> Publish.run(workspace, "client", "2.9.0", false, log));

            assertNotNull(s3.object(chunkKey(chunk)));
            assertTrue(cache(workspace).contains(chunk), cache(workspace));
        }
    }

    @Test
    void doesNotRememberAFailedUpload(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            String chunk = chunkOf("A");
            s3.failPut(chunkKey(chunk));

            assertThrows(UserError.class, () -> Publish.run(workspace, "client", "2.9.0", false, log));
            assertFalse(cache(workspace).contains(chunk), cache(workspace));

            s3.clearFailures();
            printed.reset();
            Publish.run(workspace, "client", "2.9.0", false, log);
            assertTrue(cache(workspace).contains(chunk), cache(workspace));
            assertNotNull(s3.object(chunkKey(chunk)));
        }
    }

    @Test
    void givesUpTheCacheWhenTheBucketChanges(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            Publish.run(workspace, "client", "2.9.0", false, log);
            String chunk = chunkOf("A");
            int before = s3.requests().size();

            write(workspace, ".modpack-updater/config.json", "{\"endpoint\": \"" + s3.url()
                    + "\", \"bucket\": \"other\", \"prefix\": \"gtnh\", \"path_style\": true,"
                    + " \"access_key\": \"AK\", \"secret_key\": \"SK\"}");
            Publish.run(workspace, "client", "2.9.1", false, log);
            List<String> second = s3.requests().subList(before, s3.requests().size());

            String otherChunk = "/other/gtnh/blob/" + chunk.substring(0, 2) + "/" + chunk;
            assertTrue(second.contains("HEAD " + otherChunk), second.toString());
        }
    }

    @Test
    void rebuildsACorruptedCache(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            Publish.run(workspace, "client", "2.9.0", false, log);
            String chunk = chunkOf("A");
            Files.writeString(Layout.knownBlobsFile(workspace), "not a cache\n", StandardCharsets.UTF_8);
            int before = s3.requests().size();

            Publish.run(workspace, "client", "2.9.1", false, log);
            List<String> second = s3.requests().subList(before, s3.requests().size());

            assertTrue(second.contains("HEAD " + chunkKey(chunk)), second.toString());
            assertTrue(cache(workspace).contains(chunk), cache(workspace));
        }
    }

    @Test
    void remembersEveryObjectUploadedInParallel(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            for (int i = 0; i < 8; i++) {
                write(workspace, "client/base/mods/" + i + ".jar", "content-" + i);
            }

            Publish.run(workspace, "client", "2.9.0", false, log);

            String cache = cache(workspace);
            for (int i = 0; i < 8; i++) {
                assertTrue(cache.contains(chunkOf("content-" + i)), cache);
                assertTrue(cache.contains(tableOf("content-" + i).indexId()), cache);
            }
        }
    }

    private static byte[] random(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static String releasePath(String releaseId) {
        return "/pack/gtnh/metadata/client/releases/" + releaseId + ".json";
    }

    private static String releasePut(String releaseId) {
        return "PUT " + releasePath(releaseId);
    }

    private static String latestPath() {
        return "/pack/gtnh/metadata/client/latest.json";
    }

    private static String latestPut() {
        return "PUT " + latestPath();
    }

    private static String cache(Path workspace) throws IOException {
        Path file = Layout.knownBlobsFile(workspace);
        return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }

    private static String chunkKey(String hash) {
        return "/pack/gtnh/blob/" + hash.substring(0, 2) + "/" + hash;
    }

    private static String indexKey(String id) {
        return "/pack/gtnh/index/" + id.substring(0, 2) + "/" + id;
    }

    private static String line(String text, String marker) {
        for (String candidate : text.split("\n")) {
            if (candidate.contains(marker)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Path> logs(Path workspace) throws IOException {
        try (var files = Files.list(Layout.logsDir(workspace))) {
            return files.toList();
        }
    }
}

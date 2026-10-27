package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.TestArchives;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.model.ChunkTable;
import io.github.deshark42.modpackupdater.model.Chunking;
import io.github.deshark42.modpackupdater.model.FileEntry;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import io.github.deshark42.modpackupdater.model.Pointer;
import io.github.deshark42.modpackupdater.s3.BlobCache;
import io.github.deshark42.modpackupdater.security.ReleaseSigning;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock(TestLogs.RESOURCE)
class PublishTest {
    private static final String TAG = "client";
    private static final String RELEASE = "2.9.0";
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH.plus(Duration.ofDays(31)), ZoneOffset.UTC);

    @Test
    void aPublishUploadsTheMergedContentOnceAndCommitsItsSignedRelease(@TempDir Path workspace) throws Exception {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            byte[] multiChunk = TestFiles.random(Chunking.DEFAULT.max() + 1, 21);
            TestFiles.write(Layout.baseDir(workspace, TAG), "mods/raw.jar", multiChunk);
            TestWorkspace.base(workspace, TAG, "config/restore.cfg", "removed base copy");
            TestWorkspace.base(workspace, TAG, "config/cached.cfg", "cached");
            Path zip = Layout.basePackDir(workspace, TAG).resolve("download/instance.ZIP");
            TestArchives.write(zip, Map.of("mods/copy.jar", multiChunk, "mods/remove.jar", TestFiles.bytes("removed"),
                    "mods/replace.jar", TestFiles.bytes("base copy")));
            Path sevenZ = Layout.basePackDir(workspace, TAG).resolve("extra.7Z");
            TestArchives.write(sevenZ, Map.of("config/remote.cfg", TestFiles.bytes("already stored")));
            byte[] zipBytes = Files.readAllBytes(zip);
            byte[] sevenZBytes = Files.readAllBytes(sevenZ);
            TestWorkspace.overlay(workspace, TAG, "download/mods/replace.jar", "overlay");
            TestWorkspace.overlay(workspace, TAG, "config/restore.cfg", "restored overlay");
            TestWorkspace.remove(workspace, TAG, "remove.jar\nrestore.cfg\n");
            Set<String> existing = seedContent(s3, "cached");
            existing.addAll(seedContent(s3, "already stored"));
            // An independent earlier publisher populated the shared cache while publishing another tag.
            String previousRelease = key(ObjectKeys.release("server", "previous"));
            String previousPointer = key(ObjectKeys.latest("server"));
            byte[] serverManifest = TestFiles.bytes(new Manifest("server", "previous", Instant.EPOCH, Manifest.SHA256,
                    Map.of("server.cfg", new FileEntry(Sha256.ofBytes(TestFiles.bytes("cached")), 6,
                            tableOf("cached").indexId()))).toJson());
            byte[] serverLatest = TestFiles.bytes(new Pointer("previous").toJson());
            s3.put(previousRelease, serverManifest);
            s3.put(previousPointer, serverLatest);
            BlobCache before = BlobCache.open(workspace, TestWorkspace.config(s3), Duration.ofDays(40),
                    Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
            before.remember(Sha256.ofBytes(TestFiles.bytes("cached")));
            before.remember(tableOf("cached").indexId());
            logs.begin(workspace, "publish-client", CLOCK);

            Publish.run(workspace, TAG, RELEASE, 40, CLOCK);

            Map<String, byte[]> expected = new TreeMap<>(Map.of(
                    "mods/raw.jar", multiChunk,
                    "download/mods/copy.jar", multiChunk,
                    "download/mods/replace.jar", TestFiles.bytes("overlay"),
                    "config/restore.cfg", TestFiles.bytes("restored overlay"),
                    "config/cached.cfg", TestFiles.bytes("cached"),
                    "config/remote.cfg", TestFiles.bytes("already stored")));
            Manifest manifest = Manifest.parse(s3.text(releaseKey()), "published manifest");
            assertEquals(TAG, manifest.tag());
            assertEquals(RELEASE, manifest.releaseId());
            assertEquals(CLOCK.instant(), manifest.publishedAt());
            Set<String> content = assertPublishedContent(s3, manifest, expected);
            Set<String> writes = new TreeSet<>(content);
            writes.removeAll(existing);
            writes.add(releaseKey());
            writes.add(signatureKey());
            writes.add(latestKey());
            assertWrites(s3, writes);
            assertEquals(content.size() + 5, s3.objectCount(), "the prior tag retains its release and pointer");
            assertArrayEquals(serverManifest, s3.object(previousRelease));
            assertArrayEquals(serverLatest, s3.object(previousPointer));
            assertEquals(new Pointer(RELEASE), Pointer.parse(s3.text(latestKey()), "latest"));
            assertEquals("gzip", s3.contentEncoding(releaseKey()));
            assertNull(s3.contentEncoding(latestKey()));
            assertCommitOrder(s3.requests(), content);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(ReleaseSigning.publicKey(workspace.resolve(Layout.DEFAULT_PUBLIC_KEY)));
            verifier.update(TestFiles.bytes(s3.text(releaseKey())));
            assertTrue(verifier.verify(s3.object(signatureKey())), "the uploaded signature authenticates the stored manifest");
            for (FakeS3.Request request : s3.requestsDetailed()) {
                if (request.method().equals("PUT")) {
                    assertEquals(request.path().equals(latestKey()) ? ObjectKeys.NO_STORE_CACHE_CONTROL
                            : ObjectKeys.IMMUTABLE_CACHE_CONTROL, request.headers().get("cache-control"), request.path());
                }
            }
            assertFalse(s3.requests().contains("HEAD " + key(ObjectKeys.blob(Sha256.ofBytes(TestFiles.bytes("cached"))))));
            assertFalse(s3.requests().contains("HEAD " + key(ObjectKeys.index(tableOf("cached").indexId()))));
            assertEquals(1, s3.requests().stream().filter(request -> request.equals(
                    "HEAD " + key(ObjectKeys.blob(Sha256.ofBytes(TestFiles.bytes("already stored")))))).count());
            Set<String> requests = new TreeSet<>();
            content.forEach(object -> requests.add("HEAD " + object));
            requests.remove("HEAD " + key(ObjectKeys.blob(Sha256.ofBytes(TestFiles.bytes("cached")))));
            requests.remove("HEAD " + key(ObjectKeys.index(tableOf("cached").indexId())));
            requests.add("HEAD " + releaseKey());
            writes.forEach(object -> requests.add("PUT " + object));
            assertEquals(requests, new TreeSet<>(s3.requests()));
            assertEquals(requests.size(), s3.requests().size(), "the publish performs no redundant request or download");
            assertCacheAnswersWithoutRequests(workspace, s3, manifest);
            assertArrayEquals(zipBytes, Files.readAllBytes(zip));
            assertArrayEquals(sevenZBytes, Files.readAllBytes(sevenZ));
            assertArrayEquals(multiChunk, Files.readAllBytes(Layout.baseDir(workspace, TAG).resolve("mods/raw.jar")));
            assertNoStaging(workspace);
            assertTrue(logs.outText().contains("published release " + RELEASE + " of tag " + TAG), logs.outText());
        }
    }

    @Test
    void aFailedSignatureUploadPreservesLatestAndReleasesItsInputs(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            TestWorkspace.configure(workspace, s3);
            Path zip = Layout.basePackDir(workspace, TAG).resolve("instance.zip");
            TestArchives.write(zip, Map.of("mods/a.jar", TestFiles.bytes("confirmed"), "mods/b.jar", TestFiles.bytes("uploaded")));
            byte[] archiveBytes = Files.readAllBytes(zip);
            Set<String> confirmed = seedContent(s3, "confirmed");
            // An earlier publisher already owns this pointer; this run must not advance it on failure.
            byte[] oldLatest = TestFiles.bytes(new Pointer("previous").toJson());
            s3.put(latestKey(), oldLatest);
            s3.failPut(signatureKey());
            logs.begin(workspace, "publish-client", CLOCK);

            UserError error = assertThrows(UserError.class, () -> Publish.run(workspace, TAG, RELEASE, 40, CLOCK));

            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
            assertArrayEquals(oldLatest, s3.object(latestKey()), "latest does not advertise an unsigned release");
            assertNull(s3.object(signatureKey()), "a failed signature upload leaves no signature object");
            Manifest manifest = Manifest.parse(s3.text(releaseKey()), "unpublished manifest");
            Set<String> content = assertPublishedContent(s3, manifest,
                    Map.of("mods/a.jar", TestFiles.bytes("confirmed"), "mods/b.jar", TestFiles.bytes("uploaded")));
            Set<String> writes = new TreeSet<>(content);
            writes.removeAll(confirmed);
            writes.add(releaseKey());
            writes.add(signatureKey());
            assertWrites(s3, writes);
            assertEquals(content.size() + 2, s3.objectCount(), "content, an unpublished manifest and the unchanged pointer remain");
            assertFalse(s3.requests().contains("PUT " + latestKey()), "latest is never reached");
            assertTrue(s3.requests().stream().noneMatch(request -> request.startsWith("GET ")), "publishing needs no object download");
            assertCacheAnswersWithoutRequests(workspace, s3, manifest);
            assertArrayEquals(archiveBytes, Files.readAllBytes(zip));
            assertNoStaging(workspace);
        }
    }

    private static Set<String> assertPublishedContent(FakeS3 s3, Manifest manifest, Map<String, byte[]> expected)
            throws IOException {
        assertEquals(new TreeSet<>(expected.keySet()), manifest.files().keySet());
        Set<String> objects = new TreeSet<>();
        for (Map.Entry<String, byte[]> file : expected.entrySet()) {
            FileEntry entry = manifest.files().get(file.getKey());
            assertEquals(file.getValue().length, entry.size(), file.getKey());
            assertEquals(Sha256.ofBytes(file.getValue()), entry.hash(), file.getKey());
            String index = key(ObjectKeys.index(entry.indexId()));
            objects.add(index);
            ChunkTable table = ChunkTable.parse(s3.text(index), "uploaded chunk table");
            ByteArrayOutputStream restored = new ByteArrayOutputStream();
            for (String chunk : table.chunks()) {
                String blob = key(ObjectKeys.blob(chunk));
                objects.add(blob);
                restored.write(s3.object(blob));
            }
            assertArrayEquals(file.getValue(), restored.toByteArray(), file.getKey());
        }
        return objects;
    }

    private static void assertCacheAnswersWithoutRequests(Path workspace, FakeS3 s3, Manifest manifest) {
        int requests = s3.requests().size();
        BlobCache cache = BlobCache.open(workspace, TestWorkspace.config(s3), Duration.ofDays(40), CLOCK);
        var store = new io.github.deshark42.modpackupdater.s3.S3Store(TestWorkspace.config(s3), CLOCK);
        for (FileEntry entry : manifest.files().values()) {
            assertTrue(cache.indexExists(store, entry.indexId()));
            ChunkTable table = ChunkTable.parse(s3.text(key(ObjectKeys.index(entry.indexId()))), "uploaded chunk table");
            for (String chunk : table.chunks()) {
                assertTrue(cache.chunkExists(store, chunk));
            }
        }
        assertEquals(requests, s3.requests().size(), "confirmed and uploaded objects survive cache reopening without HEAD requests");
    }

    private static void assertWrites(FakeS3 s3, Set<String> expected) {
        List<String> puts = s3.requests().stream().filter(request -> request.startsWith("PUT "))
                .map(request -> request.substring("PUT ".length())).toList();
        assertEquals(expected, new TreeSet<>(puts));
        assertEquals(expected.size(), puts.size(), "every missing object is uploaded exactly once");
    }

    private static void assertCommitOrder(List<String> requests, Set<String> content) {
        int release = requests.indexOf("PUT " + releaseKey());
        int signature = requests.indexOf("PUT " + signatureKey());
        int latest = requests.indexOf("PUT " + latestKey());
        assertTrue(release >= 0 && signature > release && latest > signature, requests.toString());
        for (String object : content) {
            int put = requests.indexOf("PUT " + object);
            if (put >= 0) {
                assertTrue(put < release, "content precedes its manifest: " + object);
            }
        }
    }

    private static Set<String> seedContent(FakeS3 s3, String content) {
        String blob = key(ObjectKeys.blob(Sha256.ofBytes(TestFiles.bytes(content))));
        String index = key(ObjectKeys.index(tableOf(content).indexId()));
        s3.put(blob, TestFiles.bytes(content));
        s3.put(index, tableOf(content).bytes());
        return new HashSet<>(Set.of(blob, index));
    }

    private static ChunkTable tableOf(String content) {
        byte[] bytes = TestFiles.bytes(content);
        return new ChunkTable(Chunking.DEFAULT, bytes.length, List.of(Sha256.ofBytes(bytes)));
    }

    private static String key(String object) {
        return TestWorkspace.bucketPath(object);
    }

    private static String releaseKey() {
        return key(ObjectKeys.release(TAG, RELEASE));
    }

    private static String signatureKey() {
        return key(ObjectKeys.signature(TAG, RELEASE));
    }

    private static String latestKey() {
        return key(ObjectKeys.latest(TAG));
    }

    private static void assertNoStaging(Path workspace) throws IOException {
        Path temp = Layout.tempDir(workspace);
        if (Files.exists(temp)) {
            try (var entries = Files.list(temp)) {
                assertEquals(List.of(), entries.toList(), "publication releases every archive staging directory");
            }
        }
    }

}

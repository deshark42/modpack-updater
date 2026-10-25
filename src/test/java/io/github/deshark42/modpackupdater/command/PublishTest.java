package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import io.github.deshark42.modpackupdater.model.ChunkTable;
import io.github.deshark42.modpackupdater.model.Chunking;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.FileEntry;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import io.github.deshark42.modpackupdater.model.Pointer;
import io.github.deshark42.modpackupdater.s3.S3Store;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
        resetLog();
        captured.begin(workspace, "publish-" + tag);
        try {
            Publish.run(workspace, tag, releaseId, cacheMaxAgeDays);
        } finally {
            captured.close();
        }
    }

    /** The message body of a console line: what follows the timestamp and the level. */
    private static String body(String line) {
        String[] parts = line.trim().split("\\s+", 4);
        return parts.length < 4 ? "" : parts[3];
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
        return new S3Store(TestWorkspace.config(s3));
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
    void refusesReservedContentBeforeContactingTheBucket(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, Layout.STATE_DIR + "/" + Layout.CONFIG, "untrusted config");

            UserError error = assertThrows(UserError.class, () -> publish(workspace, TAG, "2.9.0"));

            assertTrue(error.getMessage().contains("reserved state directory"), error.getMessage());
            assertEquals(List.of(), s3.requests(), "no release, pointer, chunk or table reaches the bucket");
            assertFalse(Files.exists(Layout.knownBlobsFile(workspace)));
        }
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
            assertEquals(7, s3.objectCount(), "2 chunks, 2 chunk tables, manifest, signature and pointer");

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

    @ParameterizedTest(name = "publishes a {0} base_pack archive")
    @ValueSource(strings = {"zip", "ZIP", "7z", "7Z"})
    void publishesAnArchiveBaseWithOverlayAndRemoveRules(String extension, @TempDir Path workspace)
            throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            Files.createDirectories(Layout.baseDir(workspace, TAG));
            Path archive = Layout.basePackDir(workspace, TAG).resolve("instance." + extension);
            Map<String, byte[]> archived = Map.of(
                    "download/mods/keep.jar", TestFiles.bytes("kept from archive"),
                    "download/mods/replace.jar", TestFiles.bytes("base copy"),
                    "download/mods/remove.jar", TestFiles.bytes("remove me"),
                    "download/config/restore.cfg", TestFiles.bytes("base config"),
                    Layout.STATE_DIR + "/untrusted.json", TestFiles.bytes("untrusted state"));
            archive(archive, archived);
            byte[] archiveBytes = Files.readAllBytes(archive);
            TestWorkspace.overlay(workspace, TAG, "download/mods/replace.jar", "overlay copy");
            TestWorkspace.overlay(workspace, TAG, "download/config/restore.cfg", "restored by overlay");
            TestWorkspace.overlay(workspace, TAG, "download/config/added.cfg", "overlay addition");
            TestWorkspace.remove(workspace, TAG,
                    "remove.jar\nrestore.cfg\n/" + Layout.STATE_DIR + "/\n");

            publish(workspace, TAG, "2.9.0");

            Manifest manifest = Manifest.parse(s3.text(releaseKey("2.9.0")), "release");
            Map<String, byte[]> expected = new TreeMap<>(Map.of(
                    "download/mods/keep.jar", archived.get("download/mods/keep.jar"),
                    "download/mods/replace.jar", TestFiles.bytes("overlay copy"),
                    "download/config/restore.cfg", TestFiles.bytes("restored by overlay"),
                    "download/config/added.cfg", TestFiles.bytes("overlay addition")));
            assertEquals(List.copyOf(expected.keySet()), List.copyOf(manifest.files().keySet()));
            for (Map.Entry<String, byte[]> file : expected.entrySet()) {
                FileEntry entry = manifest.files().get(file.getKey());
                ChunkTable table = ChunkTable.parse(s3.text(indexKey(entry.indexId())), "chunk table");
                ByteArrayOutputStream uploaded = new ByteArrayOutputStream();
                for (String chunk : table.chunks()) {
                    uploaded.write(s3.object(chunkKey(chunk)));
                }
                assertArrayEquals(file.getValue(), uploaded.toByteArray(), file.getKey());
            }
            assertEquals("2.9.0", Pointer.parse(s3.text(latestKey()), "latest").releaseId());
            assertArrayEquals(archiveBytes, Files.readAllBytes(archive), "the publisher leaves its input intact");
            try (var baseFiles = Files.list(Layout.baseDir(workspace, TAG))) {
                assertEquals(0, baseFiles.count(), "the init-created directory remains empty");
            }
            assertEquals(List.of(), staged(workspace), "the completed publish releases its archive staging");
            assertTrue(captured.outText().contains("4 files"), captured.outText());
            String shown = Path.of(TAG, "base_pack", "instance." + extension).toString();
            assertTrue(captured.outText().contains("extracting " + shown), captured.outText());
            assertTrue(captured.outText().contains("extracted " + shown + ": 5 files"), captured.outText());
            String transcript = Files.readString(logs(workspace).get(0), StandardCharsets.UTF_8);
            assertTrue(transcript.contains("replaced download/mods/replace.jar ("), transcript);
            assertTrue(transcript.contains("dropped download/mods/remove.jar ("), transcript);
        }
    }

    @ParameterizedTest(name = "a broken {0} leaves no storage or staging side effects")
    @ValueSource(strings = {"zip", "7z"})
    void rejectsABrokenArchiveBeforeContactingTheBucket(String extension, @TempDir Path workspace)
            throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            Path goodArchive = Layout.basePackDir(workspace, TAG).resolve("a-good.zip");
            archive(goodArchive, Map.of("kept.txt", TestFiles.bytes("a valid earlier archive")));
            byte[] goodBytes = Files.readAllBytes(goodArchive);
            Path archive = Layout.basePackDir(workspace, TAG).resolve("z-broken." + extension);
            archive(archive, Map.of("mods/a.jar", TestFiles.bytes("archive content")));
            byte[] complete = Files.readAllBytes(archive);
            byte[] broken = Arrays.copyOf(complete, complete.length - 10);
            Files.write(archive, broken);

            UserError error = assertThrows(UserError.class, () -> publish(workspace, TAG, "2.9.0"));

            assertTrue(error.getMessage().contains("archive"), error.getMessage());
            assertTrue(error.getMessage().contains(archive.toString()), error.getMessage());
            assertEquals(List.of(), s3.requests(), "no upload or metadata request happens for an unreadable base");
            assertEquals(0, s3.objectCount(), "no release, signature, pointer, chunk or table is published");
            assertFalse(Files.exists(Layout.knownBlobsFile(workspace)));
            assertEquals(List.of(), staged(workspace), "failed extraction leaves no staging files");
            assertArrayEquals(broken, Files.readAllBytes(archive), "failed extraction preserves the source archive");
            assertArrayEquals(goodBytes, Files.readAllBytes(goodArchive), "earlier archives remain intact");
        }
    }

    @ParameterizedTest(name = "merges mapped ZIP and 7z archives with raw base={0}")
    @ValueSource(booleans = {false, true})
    void mergesRawFilesAndMultipleMappedArchivesWithoutRecursivelyUnpacking(boolean withRawBase,
            @TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            Path nestedArchive = workspace.resolve("nested.zip");
            archive(nestedArchive, Map.of("must-stay-packed.txt", TestFiles.bytes("nested content")));
            byte[] nestedBytes = Files.readAllBytes(nestedArchive);
            Path first = Layout.basePackDir(workspace, TAG).resolve("one_path/test.zip");
            Path second = Layout.basePackDir(workspace, TAG).resolve("one_path/more.7z");
            Path root = Layout.basePackDir(workspace, TAG).resolve("top.zip");
            archive(first, Map.of(
                    "root.txt", TestFiles.bytes("root in test.zip"),
                    "second_path/second.txt", TestFiles.bytes("nested path in test.zip"),
                    "embedded.zip", nestedBytes,
                    "empty_shared/", new byte[0]));
            archive(second, Map.of(
                    "extra.txt", TestFiles.bytes("from 7z"),
                    "second_path/third.txt", TestFiles.bytes("merged directory from 7z"),
                    "empty_shared/", new byte[0]));
            archive(root, Map.of("global.txt", TestFiles.bytes("from root archive")));
            Map<Path, byte[]> inputs = Map.of(first, Files.readAllBytes(first),
                    second, Files.readAllBytes(second), root, Files.readAllBytes(root));
            Map<String, byte[]> expected = new TreeMap<>(Map.of(
                    "one_path/root.txt", TestFiles.bytes("root in test.zip"),
                    "one_path/second_path/second.txt", TestFiles.bytes("nested path in test.zip"),
                    "one_path/embedded.zip", nestedBytes,
                    "one_path/extra.txt", TestFiles.bytes("from 7z"),
                    "one_path/second_path/third.txt", TestFiles.bytes("merged directory from 7z"),
                    "global.txt", TestFiles.bytes("from root archive")));
            if (withRawBase) {
                TestWorkspace.base(workspace, TAG, "one_path/raw.txt", "raw base file");
                TestWorkspace.base(workspace, TAG, "one_path/second_path/raw.txt", "raw shared directory");
                TestFiles.write(Layout.baseDir(workspace, TAG), "one_path/raw.zip", nestedBytes);
                Files.createDirectories(Layout.baseDir(workspace, TAG).resolve("one_path/empty_shared"));
                Files.createDirectories(Layout.basePackDir(workspace, TAG).resolve("one_path/raw.txt"));
                expected.put("one_path/raw.txt", TestFiles.bytes("raw base file"));
                expected.put("one_path/second_path/raw.txt", TestFiles.bytes("raw shared directory"));
                expected.put("one_path/raw.zip", nestedBytes);
            }

            publish(workspace, TAG, "2.9.0");

            assertPublishedFiles(s3, "2.9.0", expected);
            assertEquals("2.9.0", Pointer.parse(s3.text(latestKey()), "latest").releaseId());
            for (Map.Entry<Path, byte[]> input : inputs.entrySet()) {
                assertArrayEquals(input.getValue(), Files.readAllBytes(input.getKey()), input.getKey().toString());
            }
            if (withRawBase) {
                assertEquals("raw base file", Files.readString(Layout.baseDir(workspace, TAG)
                        .resolve("one_path/raw.txt")));
                assertEquals("raw shared directory", Files.readString(Layout.baseDir(workspace, TAG)
                        .resolve("one_path/second_path/raw.txt")));
                assertArrayEquals(nestedBytes, Files.readAllBytes(Layout.baseDir(workspace, TAG)
                        .resolve("one_path/raw.zip")));
            } else {
                assertFalse(Files.exists(Layout.baseDir(workspace, TAG)), "a pack-only publish needs no base directory");
            }
            assertEquals(List.of(), staged(workspace), "all extracted archives are released after uploading");
        }
    }

    @ParameterizedTest(name = "an empty {0} is a usable input")
    @ValueSource(strings = {"base", "base_pack", "both"})
    void publishesEmptyInputDirectories(String input, @TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            if (!input.equals("base_pack")) {
                Files.createDirectories(Layout.baseDir(workspace, TAG));
            }
            if (!input.equals("base")) {
                Files.createDirectories(Layout.basePackDir(workspace, TAG));
            }

            publish(workspace, TAG, "2.9.0");

            assertPublishedFiles(s3, "2.9.0", Map.of());
            assertEquals("2.9.0", Pointer.parse(s3.text(latestKey()), "latest").releaseId());
            assertEquals(List.of(), staged(workspace));
        }
    }

    @ParameterizedTest(name = "{0}: {1} conflicts with packed {2} before filtering")
    @CsvSource({
            "base, collision.txt, collision.txt",
            "base, collision, collision/child.txt",
            "base, collision/child.txt, collision",
            "base, collision, collision/",
            "base-directory, collision, collision",
            "pack, collision.txt, collision.txt",
            "pack, collision, collision/child.txt",
            "pack, collision/child.txt, collision",
            "pack, collision, collision/",
            "pack, collision/, collision"
    })
    void rejectsMergedInputCollisionsBeforeRemoveOverlayAndAnyBucketAccess(String firstSource,
            String firstPath, String secondPath, @TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            Map<Path, byte[]> inputs = new HashMap<>();
            if (firstSource.equals("base")) {
                TestWorkspace.base(workspace, TAG, firstPath, "identical bytes");
            } else if (firstSource.equals("base-directory")) {
                Files.createDirectories(Layout.baseDir(workspace, TAG).resolve(firstPath));
            } else {
                Path first = Layout.basePackDir(workspace, TAG).resolve("a-first.zip");
                archive(first, Map.of(firstPath,
                        firstPath.endsWith("/") ? new byte[0] : TestFiles.bytes("identical bytes")));
                inputs.put(first, Files.readAllBytes(first));
            }
            Path second = Layout.basePackDir(workspace, TAG).resolve("b-second.7z");
            archive(second, Map.of(secondPath,
                    secondPath.endsWith("/") ? new byte[0] : TestFiles.bytes("identical bytes")));
            inputs.put(second, Files.readAllBytes(second));
            TestWorkspace.remove(workspace, TAG, "*\n");
            TestWorkspace.overlay(workspace, TAG, secondPath.endsWith("/") ? secondPath + "overlay.txt" : secondPath,
                    "an overlay cannot hide a base collision");

            UserError error = assertThrows(UserError.class, () -> publish(workspace, TAG, "2.9.0"));

            assertTrue(error.getMessage().contains("collision"), error.getMessage());
            assertTrue(error.getMessage().contains("conflict"), error.getMessage());
            assertNoStorageOrStagingSideEffects(s3, workspace);
            for (Map.Entry<Path, byte[]> input : inputs.entrySet()) {
                assertArrayEquals(input.getValue(), Files.readAllBytes(input.getKey()), input.getKey().toString());
            }
            if (firstSource.equals("base")) {
                assertEquals("identical bytes", Files.readString(Layout.baseDir(workspace, TAG).resolve(firstPath)));
            } else if (firstSource.equals("base-directory")) {
                assertTrue(Files.isDirectory(Layout.baseDir(workspace, TAG).resolve(firstPath)));
            }
        }
    }

    @ParameterizedTest(name = "native path identity: {0} {1} versus packed {2}")
    @CsvSource({
            "base, mods/A.jar, mods/a.jar",
            "base, Mods/a.jar, mods/a.jar",
            "base, Mods, mods/a.jar",
            "base, Mods/a.jar, mods",
            "base, Mods, mods/",
            "base-directory, Mods, mods",
            "pack, mods/A.jar, mods/a.jar",
            "pack, Mods/a.jar, mods/a.jar",
            "pack, Mods, mods/a.jar",
            "pack, Mods/a.jar, mods",
            "pack, Mods, mods/",
            "pack, Mods/, mods"
    })
    void handlesCaseOnlyMergedPathsAccordingToThePublishersFileSystem(String firstSource,
            String firstPath, String secondPath, @TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            boolean aliasesOnThisFileSystem = workspace.resolve("Mods").equals(workspace.resolve("mods"));
            byte[] firstBytes = TestFiles.bytes("first source content");
            byte[] secondBytes = TestFiles.bytes("second source content");
            Map<Path, byte[]> archiveInputs = new HashMap<>();
            Map<String, byte[]> expected = new TreeMap<>();
            if (firstSource.equals("base")) {
                TestFiles.write(Layout.baseDir(workspace, TAG), firstPath, firstBytes);
                expected.put(firstPath, firstBytes);
            } else if (firstSource.equals("base-directory")) {
                Files.createDirectories(Layout.baseDir(workspace, TAG).resolve(firstPath));
            } else {
                Path first = Layout.basePackDir(workspace, TAG).resolve("a-first.zip");
                archive(first, Map.of(firstPath, firstPath.endsWith("/") ? new byte[0] : firstBytes));
                archiveInputs.put(first, Files.readAllBytes(first));
                if (!firstPath.endsWith("/")) {
                    expected.put(firstPath, firstBytes);
                }
            }
            Path second = Layout.basePackDir(workspace, TAG).resolve("b-second.7z");
            archive(second, Map.of(secondPath, secondPath.endsWith("/") ? new byte[0] : secondBytes));
            archiveInputs.put(second, Files.readAllBytes(second));
            if (!secondPath.endsWith("/")) {
                expected.put(secondPath, secondBytes);
            }
            if (aliasesOnThisFileSystem) {
                TestWorkspace.remove(workspace, TAG, "*\n");
                TestWorkspace.overlay(workspace, TAG,
                        secondPath.endsWith("/") ? secondPath + "overlay.txt" : secondPath,
                        "an overlay cannot hide a native path collision");

                UserError error = assertThrows(UserError.class, () -> publish(workspace, TAG, "2.9.0"));

                assertTrue(error.getMessage().contains("collision"), error.getMessage());
                assertTrue(error.getMessage().contains("conflict"), error.getMessage());
                assertNoStorageOrStagingSideEffects(s3, workspace);
            } else {
                publish(workspace, TAG, "2.9.0");

                assertPublishedFiles(s3, "2.9.0", expected);
                assertEquals("2.9.0", Pointer.parse(s3.text(latestKey()), "latest").releaseId());
            }
            for (Map.Entry<Path, byte[]> input : archiveInputs.entrySet()) {
                assertArrayEquals(input.getValue(), Files.readAllBytes(input.getKey()), input.getKey().toString());
            }
            if (firstSource.equals("base")) {
                assertArrayEquals(firstBytes, Files.readAllBytes(Layout.baseDir(workspace, TAG).resolve(firstPath)));
            } else if (firstSource.equals("base-directory")) {
                assertTrue(Files.isDirectory(Layout.baseDir(workspace, TAG).resolve(firstPath)));
            }
            assertEquals(List.of(), staged(workspace), "native path handling releases every archive staging directory");
        }
    }

    @ParameterizedTest(name = "unsafe paths in a {0} archive fail without publishing")
    @ValueSource(strings = {"zip", "7z"})
    void rejectsUnsafePackedPathsAndCleansEarlierExtractions(String extension, @TempDir Path workspace)
            throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            Path good = Layout.basePackDir(workspace, TAG).resolve("a-good.zip");
            archive(good, Map.of("good.txt", TestFiles.bytes("good archive content")));
            Path unsafe = Layout.basePackDir(workspace, TAG).resolve("z-unsafe." + extension);
            archive(unsafe, Map.of("../escape.txt", TestFiles.bytes("must not escape"),
                    "safe.txt", TestFiles.bytes("must not publish a partial archive")));
            byte[] goodBytes = Files.readAllBytes(good);
            byte[] unsafeBytes = Files.readAllBytes(unsafe);

            assertThrows(UserError.class, () -> publish(workspace, TAG, "2.9.0"));

            assertNoStorageOrStagingSideEffects(s3, workspace);
            assertFalse(Files.exists(workspace.resolve("escape.txt")));
            assertArrayEquals(goodBytes, Files.readAllBytes(good));
            assertArrayEquals(unsafeBytes, Files.readAllBytes(unsafe));
        }
    }

    @ParameterizedTest(name = "base_pack rejects the non-archive file {0}")
    @ValueSource(strings = {"z-readme.txt", "nested/z-mod.jar"})
    void rejectsNonArchivePackFilesWithoutPublishingOrLeavingStaging(String name, @TempDir Path workspace)
            throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            Path good = Layout.basePackDir(workspace, TAG).resolve("a-good.zip");
            archive(good, Map.of("good.txt", TestFiles.bytes("good archive content")));
            byte[] goodBytes = Files.readAllBytes(good);
            TestFiles.write(Layout.basePackDir(workspace, TAG), name, "not a supported archive");

            UserError error = assertThrows(UserError.class, () -> publish(workspace, TAG, "2.9.0"));

            assertTrue(error.getMessage().contains(Path.of(name).getFileName().toString()), error.getMessage());
            assertNoStorageOrStagingSideEffects(s3, workspace);
            assertArrayEquals(goodBytes, Files.readAllBytes(good));
            assertEquals("not a supported archive", Files.readString(Layout.basePackDir(workspace, TAG).resolve(name)));
        }
    }

    @ParameterizedTest(name = "legacy {0} requests migration instead of publishing empty inputs")
    @ValueSource(strings = {"base.zip", "base.7z"})
    void rejectsLegacyRootArchivesWithAMigrationHint(String name, @TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            Files.createDirectories(Layout.baseDir(workspace, TAG));
            Files.createDirectories(Layout.basePackDir(workspace, TAG));
            Path legacy = Layout.tagRoot(workspace, TAG).resolve(name);
            archive(legacy, Map.of("mods/a.jar", TestFiles.bytes("legacy content")));
            byte[] original = Files.readAllBytes(legacy);

            UserError error = assertThrows(UserError.class, () -> publish(workspace, TAG, "2.9.0"));

            assertTrue(error.getMessage().contains(name), error.getMessage());
            assertTrue(error.getMessage().contains("base_pack"), error.getMessage());
            assertNoStorageOrStagingSideEffects(s3, workspace);
            assertArrayEquals(original, Files.readAllBytes(legacy));
        }
    }

    private static void assertPublishedFiles(FakeS3 s3, String releaseId, Map<String, byte[]> expected)
            throws IOException {
        Manifest manifest = Manifest.parse(s3.text(releaseKey(releaseId)), "release");
        assertEquals(List.copyOf(new TreeMap<>(expected).keySet()), List.copyOf(manifest.files().keySet()));
        for (Map.Entry<String, byte[]> file : expected.entrySet()) {
            FileEntry entry = manifest.files().get(file.getKey());
            ChunkTable table = ChunkTable.parse(s3.text(indexKey(entry.indexId())), "chunk table");
            ByteArrayOutputStream uploaded = new ByteArrayOutputStream();
            for (String chunk : table.chunks()) {
                uploaded.write(s3.object(chunkKey(chunk)));
            }
            assertArrayEquals(file.getValue(), uploaded.toByteArray(), file.getKey());
        }
    }

    private static void assertNoStorageOrStagingSideEffects(FakeS3 s3, Path workspace) throws IOException {
        assertEquals(List.of(), s3.requests(), "invalid merged inputs cause no metadata request or upload");
        assertEquals(0, s3.objectCount(), "no release, signature, pointer, chunk or table is published");
        assertFalse(Files.exists(Layout.knownBlobsFile(workspace)), "no existence cache is written");
        assertEquals(List.of(), staged(workspace), "all archive extractions are cleaned up");
    }

    private static void archive(Path archive, Map<String, byte[]> files) throws IOException {
        Files.createDirectories(archive.getParent());
        if (archive.toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
                for (Map.Entry<String, byte[]> file : new TreeMap<>(files).entrySet()) {
                    output.putNextEntry(new ZipEntry(file.getKey()));
                    output.write(file.getValue());
                    output.closeEntry();
                }
            }
        } else {
            try (SevenZOutputFile output = new SevenZOutputFile(archive.toFile())) {
                for (Map.Entry<String, byte[]> file : new TreeMap<>(files).entrySet()) {
                    SevenZArchiveEntry entry = new SevenZArchiveEntry();
                    entry.setName(file.getKey());
                    entry.setDirectory(file.getKey().endsWith("/"));
                    entry.setSize(file.getValue().length);
                    output.putArchiveEntry(entry);
                    output.write(file.getValue());
                    output.closeArchiveEntry();
                }
            }
        }
    }

    private static List<Path> staged(Path workspace) throws IOException {
        Path temp = Layout.tempDir(workspace);
        if (!Files.exists(temp)) {
            return List.of();
        }
        try (var entries = Files.list(temp)) {
            return entries.toList();
        }
    }

    @Test
    void declaresHowLongEachKindOfObjectMayBeCached(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");

            publish(workspace, TAG, "2.9.0");

            Map<String, String> sent = new HashMap<>();
            for (FakeS3.Request request : s3.requestsDetailed()) {
                if (request.method().equals("PUT") && request.headers().get("cache-control") != null) {
                    sent.put(request.path(), request.headers().get("cache-control"));
                }
            }
            String chunk = chunkKey(chunkOf("A"));
            String table = indexKey(tableOf("A").indexId());
            String release = releaseKey("2.9.0");
            assertEquals(ObjectKeys.IMMUTABLE_CACHE_CONTROL, sent.get(chunk), sent.toString());
            assertEquals(ObjectKeys.IMMUTABLE_CACHE_CONTROL, sent.get(table), sent.toString());
            assertEquals(ObjectKeys.IMMUTABLE_CACHE_CONTROL, sent.get(release), sent.toString());
            assertEquals(ObjectKeys.IMMUTABLE_CACHE_CONTROL,
                    sent.get(TestWorkspace.bucketPath(ObjectKeys.signature(TAG, "2.9.0"))), sent.toString());
            assertEquals(ObjectKeys.NO_STORE_CACHE_CONTROL, sent.get(latestKey()), sent.toString());
            assertEquals(5, sent.size(), sent.toString());
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
    void reportsATagWithoutEitherBaseInputDirectory(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);

            UserError error = assertThrows(UserError.class,
                    () -> publish(workspace, TAG, "2.9.0"));

            assertTrue(error.getMessage().contains("base"), error.getMessage());
            assertNoStorageOrStagingSideEffects(s3, workspace);
        }
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
            assertEquals(9, s3.objectCount(), "2 chunks, 2 chunk tables, 2 manifests, 2 signatures and the pointer");
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
            assertTrue(captured.outText().contains(
                    "planned the upload: 0 chunks to upload, 1 chunk already in the bucket"), captured.outText());
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
            assertTrue(text.contains("publishing release 2.9.0 of tag client"), text);
            assertTrue(text.contains("uploading mods/a.jar"), text);
            assertTrue(text.contains("published release 2.9.0 of tag client as the latest in "), text);
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
            assertTrue(text.contains(
                    "planned the upload: 1 chunk to upload, 2 chunks already in the bucket"), text);
            assertTrue(text.contains("uploaded 1 file, "), text);
            assertTrue(text.contains("2 chunks already in the bucket"), text);
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
            String text = Files.readString(logs(workspace).get(0), StandardCharsets.UTF_8);
            assertTrue(text.contains("replaced mods/a.jar ("), text);
            assertTrue(text.contains("added config/mine.json ("), text);
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
            assertTrue(captured.outText().contains(
                    "removed 3 files from " + Path.of(TAG, "base") + " by the 3 rules in "
                            + Path.of(TAG, "remove.txt")),
                    captured.outText());
            assertTrue(captured.errText().contains("matched nothing: ghost.jar"), captured.errText());
            String transcript = Files.readString(logs(workspace).get(0), StandardCharsets.UTF_8);
            assertTrue(transcript.contains("dropped config/secret.cfg ("), transcript);
            assertTrue(transcript.contains("dropped config/deep/other.cfg ("), transcript);
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
    void reportsTheRunFromStartToFinish(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");
            publish(workspace, TAG, "2.9.0");

            List<String> lines = captured.outText().lines().toList();
            List<String> bodies = lines.stream().map(PublishTest::body).toList();
            assertTrue(bodies.get(0).startsWith("logging this run to "), lines.toString());
            assertTrue(bodies.contains("publishing release 2.9.0 of tag client"), lines.toString());
            assertTrue(bodies.stream().anyMatch(
                    body -> body.startsWith("scanned " + Path.of(TAG, "base") + ": 1 file")),
                    lines.toString());
            assertTrue(bodies.stream().anyMatch(body -> body.equals("split 1 file into 1 chunk")),
                    lines.toString());
            assertTrue(bodies.stream().anyMatch(body -> body.startsWith("planned the upload: 1 chunk")),
                    lines.toString());
            assertTrue(bodies.stream().anyMatch(body -> body.startsWith("wrote ")), lines.toString());
            assertTrue(bodies.get(bodies.size() - 2).startsWith("uploaded 1 file, "), lines.toString());
            assertTrue(bodies.get(bodies.size() - 1)
                    .startsWith("published release 2.9.0 of tag client as the latest in "), lines.toString());
            for (String heading : List.of("inputs", "transfer", "commit")) {
                assertFalse(bodies.contains(heading), "no bare stage heading: " + lines);
            }
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
            assertTrue(captured.outText().contains("uploaded nothing: 1 chunk already in the bucket"),
                    captured.outText());
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
            Path base = Layout.baseDir(workspace, TAG);
            Files.delete(base.resolve("mods/a.jar"));
            Files.delete(base.resolve("mods"));
            Files.delete(base);
            Path archive = Layout.basePackDir(workspace, TAG).resolve("replacement.zip");
            archive(archive, Map.of("mods/a.jar", TestFiles.bytes("A"), "mods/b.jar", TestFiles.bytes("B")));
            byte[] archiveBytes = Files.readAllBytes(archive);
            String chunk = chunkOf("B");
            s3.failPut(releaseKey("2.9.1"));

            UserError error = assertThrows(UserError.class,
                    () -> publish(workspace, TAG, "2.9.1"));

            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
            assertNotNull(s3.object(chunkKey(chunk)), "the chunks uploaded before the failure stay");
            assertTrue(cache(workspace).contains(chunk), cache(workspace));
            assertNull(s3.object(releaseKey("2.9.1")), "a failed publish writes no manifest");
            assertEquals(latest, s3.text(latestKey()), "latest still points at the release it pointed at");
            assertEquals(List.of(), staged(workspace), "a failed publish releases its archive staging");
            assertArrayEquals(archiveBytes, Files.readAllBytes(archive), "the publisher leaves its input intact");
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

package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.LogSink;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Chunker;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.log.Log;
import io.github.deshark42.modpackupdater.model.ChunkTable;
import io.github.deshark42.modpackupdater.model.Chunking;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import io.github.deshark42.modpackupdater.model.Pointer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateTest {

    private static final String TAG = "client";

    private LogSink sink = new LogSink();
    private Log log = sink.log(Log.Level.VERBOSE);

    private void resetLog() {
        sink = new LogSink();
        log = sink.log(Log.Level.VERBOSE);
    }

    /** Replaces the tag's base tree with the given files, then publishes it, the way a publisher works. */
    private void publish(Path publisher, String tag, String releaseId, Map<String, byte[]> files)
            throws IOException {
        Path base = Layout.baseDir(publisher, tag);
        if (Files.isDirectory(base)) {
            try (var walk = Files.walk(base)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            TestFiles.write(base, file.getKey(), file.getValue());
        }
        Publish.run(publisher, tag, releaseId, false, log);
    }

    private static String applied(Path workspace, String tag) {
        return Pointer.read(Layout.currentFile(workspace, tag)).releaseId();
    }

    private static String releaseKey(String tag, String releaseId) {
        return TestWorkspace.bucketPath(ObjectKeys.release(tag, releaseId));
    }

    private static String latestKey(String tag) {
        return TestWorkspace.bucketPath(ObjectKeys.latest(tag));
    }

    private static String chunkKey(String hash) {
        return TestWorkspace.bucketPath(ObjectKeys.blob(hash));
    }

    private static String indexKey(String id) {
        return TestWorkspace.bucketPath(ObjectKeys.index(id));
    }

    private static Manifest manifest(FakeS3 s3, String tag, String releaseId) {
        return Manifest.parse(s3.text(releaseKey(tag, releaseId)), "release " + releaseId);
    }

    /**
     * The bytes a run has to fetch besides chunks: the latest pointer, the target manifest, and one
     * request per distinct chunk table of the paths whose content it has to assemble.
     */
    private static long metadataBytes(FakeS3 s3, String tag, String releaseId, List<String> paths) {
        Manifest manifest = manifest(s3, tag, releaseId);
        long bytes = s3.object(latestKey(tag)).length + s3.object(releaseKey(tag, releaseId)).length;
        Set<String> tables = new HashSet<>();
        for (String path : paths) {
            tables.add(manifest.files().get(path).indexId());
        }
        for (String table : tables) {
            bytes += s3.object(indexKey(table)).length;
        }
        return bytes;
    }

    private static List<Chunker.Cut> cuts(Path dir, byte[] content) throws IOException {
        Path file = Files.createTempFile(dir, "sample-", ".bin");
        try {
            Files.write(file, content);
            List<Chunker.Cut> cuts = new ArrayList<>();
            Chunker.split(file, Chunking.DEFAULT, cuts::add);
            return cuts;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static Set<String> hashes(List<Chunker.Cut> cuts) {
        Set<String> hashes = new HashSet<>();
        for (Chunker.Cut cut : cuts) {
            hashes.add(cut.hash());
        }
        return hashes;
    }

    private static List<String> newHashes(List<Chunker.Cut> cuts, Set<String> have) {
        List<String> missing = new ArrayList<>();
        for (Chunker.Cut cut : cuts) {
            if (!have.contains(cut.hash())) {
                missing.add(cut.hash());
            }
        }
        return missing;
    }

    private static long bytesOf(List<Chunker.Cut> cuts, List<String> hashes) {
        Set<String> wanted = new HashSet<>(hashes);
        long bytes = 0;
        for (Chunker.Cut cut : cuts) {
            if (wanted.contains(cut.hash())) {
                bytes += cut.length();
            }
        }
        return bytes;
    }

    /** The number the closing summary reports on its "reuse" line. */
    private static int reportedReuse(String output) {
        List<String> lines = output.lines().toList();
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (line.strip().startsWith("reuse ")) {
                return Integer.parseInt(line.strip().split("\\s+")[1]);
            }
        }
        throw new AssertionError("no reuse summary in:\n" + output);
    }

    private static boolean hasStep(String output, String verb) {
        return output.lines().anyMatch(line -> line.startsWith("  " + verb + " "));
    }

    private static long getCount(FakeS3 s3, String key) {
        return s3.requests().stream().filter(request -> request.equals("GET " + key)).count();
    }

    /** The run's staging directory holds no chunk any more: the failed run cleaned up after itself. */
    private static void assertNothingStaged(Path workspace) throws IOException {
        Path temp = Layout.tempDir(workspace);
        if (Files.exists(temp)) {
            try (var walk = Files.walk(temp)) {
                assertEquals(List.of(), walk.filter(Files::isRegularFile).toList(), "nothing is staged");
            }
        }
    }

    private static List<Path> logs(Path workspace) throws IOException {
        try (var files = Files.list(Layout.logsDir(workspace))) {
            return files.toList();
        }
    }

    /** The chunk table the publisher recorded for one file, next to the cuts of its content. */
    private record ChunkTableSplit(ChunkTable table, List<Chunker.Cut> cuts) {
    }

    private static ChunkTableSplit split(Path publisher, String tag, String relative) throws IOException {
        List<Chunker.Cut> cuts = new ArrayList<>();
        ChunkTable table = Chunker.split(Layout.baseDir(publisher, tag).resolve(relative), Chunking.DEFAULT,
                cuts::add).table();
        return new ChunkTableSplit(table, cuts);
    }

    @Test
    void firstUpdateAssemblesEveryFileFromTheChunksInTheBucket(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] jar = TestFiles.random(300_000, 11);
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of(
                    "mods/a.jar", jar,
                    "mods/b.jar", TestFiles.bytes("L"),
                    "libraries/lwjgl.jar", TestFiles.bytes("L"),
                    "mmc-pack.json", TestFiles.bytes("{}"),
                    "config/empty.cfg", new byte[0]));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            resetLog();

            Update.run(player, TAG, null, false, log);

            assertArrayEquals(jar, Files.readAllBytes(player.resolve("mods/a.jar")));
            assertEquals("L", Files.readString(player.resolve("mods/b.jar")));
            assertEquals("L", Files.readString(player.resolve("libraries/lwjgl.jar")));
            assertEquals("{}", Files.readString(player.resolve("mmc-pack.json")));
            assertEquals(0, Files.size(player.resolve("config/empty.cfg")));
            assertEquals("2.9.0", applied(player, TAG));
            assertTrue(Files.isRegularFile(Layout.releaseFile(player, TAG, "2.9.0")));

            List<String> lines = sink.outText().lines().toList();
            assertEquals(5, lines.stream().filter(line -> line.startsWith("    ")).count(),
                    "one item line per file, none per chunk: " + lines);
            assertTrue(hasStep(sink.outText(), "download"), lines.toString());
            assertEquals(0, reportedReuse(sink.outText()), "a first update has no local copy to reuse");

            ChunkTableSplit split = split(publisher, TAG, "mods/a.jar");
            assertTrue(split.table().chunks().size() > 1, "the sample is split into several chunks");
            assertEquals(1, getCount(s3, chunkKey(Sha256.ofBytes(TestFiles.bytes("L")))),
                    "a chunk two files share is fetched once: " + s3.requests());
            assertEquals(1, getCount(s3, indexKey(split.table().indexId())));
            String shared = manifest(s3, TAG, "2.9.0").files().get("mods/b.jar").indexId();
            assertEquals(1, getCount(s3, indexKey(shared)),
                    "two files with the same content share one chunk table: " + s3.requests());
            assertEquals(metadataBytes(s3, TAG, "2.9.0", List.of("mods/a.jar", "mods/b.jar",
                    "libraries/lwjgl.jar", "mmc-pack.json", "config/empty.cfg")) + jar.length + 1 + 2,
                    s3.downloadedBytes(), "a first update fetches every distinct chunk once");

            resetLog();
            Update.run(player, TAG, null, false, log);
            assertTrue(sink.outText().contains("already at 2.9.0"), sink.outText());
            assertTrue(sink.outText().contains("  version   2.9.0, unchanged"), sink.outText());
        }
    }

    @Test
    void updateFromThePreviousVersionDownloadsOnlyTheChunksThatChanged(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] before = TestFiles.random(300_000, 21);
            byte[] after = before.clone();
            after[150_000] ^= 0x7f;
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", before));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Update.run(player, TAG, null, false, log);
            long already = s3.downloadedBytes();

            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", after));
            resetLog();
            Update.run(player, TAG, null, false, log);
            long delta = s3.downloadedBytes() - already;

            assertArrayEquals(after, Files.readAllBytes(player.resolve("mods/a.jar")));
            List<Chunker.Cut> oldCuts = cuts(root, before);
            List<Chunker.Cut> newCuts = cuts(root, after);
            List<String> missing = newHashes(newCuts, hashes(oldCuts));
            int expectedReuse = newCuts.size() - missing.size();
            assertTrue(expectedReuse > 0, "most chunks of the file are unchanged");
            assertEquals(expectedReuse, reportedReuse(sink.outText()));
            assertTrue(sink.outText().contains(" reused from "), sink.outText());
            assertEquals(bytesOf(newCuts, missing)
                    + metadataBytes(s3, TAG, "2.9.1", List.of("mods/a.jar")), delta,
                    "only the changed chunks and the metadata came over the wire");
            assertTrue(delta < before.length / 2, "far less than the file: " + delta + " of " + before.length);
            assertArrayEquals(before, Files.readAllBytes(
                    Layout.backupDir(player, TAG, "2.9.0").resolve("mods/a.jar")));
            assertEquals("2.9.1", applied(player, TAG));
        }
    }

    @Test
    void reusesChunksThatAnotherFilesBackupCopyAlreadyHas(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] original = TestFiles.random(300_000, 31);
            byte[] changed = original.clone();
            changed[changed.length / 2] ^= 0x55;
            byte[] other = TestFiles.random(30_000, 32);
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", original, "mods/b.jar", other));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Update.run(player, TAG, null, false, log);
            long already = s3.downloadedBytes();

            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", changed, "mods/b.jar", original));
            resetLog();
            Update.run(player, TAG, null, false, log);
            long delta = s3.downloadedBytes() - already;

            assertArrayEquals(changed, Files.readAllBytes(player.resolve("mods/a.jar")));
            assertArrayEquals(original, Files.readAllBytes(player.resolve("mods/b.jar")));
            List<Chunker.Cut> changedCuts = cuts(root, changed);
            List<Chunker.Cut> originalCuts = cuts(root, original);
            Set<String> oldHashes = hashes(cuts(root, original));
            oldHashes.addAll(hashes(cuts(root, other)));
            Set<String> needed = hashes(changedCuts);
            needed.addAll(hashes(originalCuts));
            List<String> missing = new ArrayList<>();
            for (String hash : needed) {
                if (!oldHashes.contains(hash)) {
                    missing.add(hash);
                }
            }
            assertEquals(needed.size() - missing.size(), reportedReuse(sink.outText()));
            assertEquals(bytesOf(changedCuts, missing)
                    + metadataBytes(s3, TAG, "2.9.1", List.of("mods/a.jar", "mods/b.jar")), delta,
                    "the new b.jar has no chunk of its own to fetch: a.jar's backup copy has them");
            assertTrue(delta < original.length,
                    "b.jar's content was reused, not fetched again: " + delta);
        }
    }

    @Test
    void reusesTheChunksOfTheDeletedCopyWhenAFileOnlyChangedItsName(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] before = TestFiles.random(300_000, 35);
            byte[] after = before.clone();
            after[200_000] ^= 0x21;
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a-1.0.jar", before));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Update.run(player, TAG, null, false, log);
            long already = s3.downloadedBytes();

            publish(publisher, TAG, "2.9.1", Map.of("mods/a-1.1.jar", after));
            resetLog();
            Update.run(player, TAG, null, false, log);
            long delta = s3.downloadedBytes() - already;

            assertArrayEquals(after, Files.readAllBytes(player.resolve("mods/a-1.1.jar")));
            assertFalse(Files.exists(player.resolve("mods/a-1.0.jar")), "the old name is gone");
            List<Chunker.Cut> oldCuts = cuts(root, before);
            List<Chunker.Cut> newCuts = cuts(root, after);
            List<String> missing = newHashes(newCuts, hashes(oldCuts));
            int expectedReuse = newCuts.size() - missing.size();
            assertTrue(expectedReuse > 0, "the renamed file kept most of its chunks");
            assertEquals(expectedReuse, reportedReuse(sink.outText()),
                    "the deleted copy's chunks are reused: " + sink.outText());
            assertEquals(bytesOf(newCuts, missing)
                    + metadataBytes(s3, TAG, "2.9.1", List.of("mods/a-1.1.jar")), delta,
                    "only the changed chunks and the metadata came over the wire");
            assertTrue(delta < before.length / 2, "far less than the file: " + delta + " of " + before.length);
            assertArrayEquals(before, Files.readAllBytes(
                    Layout.backupDir(player, TAG, "2.9.0").resolve("mods/a-1.0.jar")));
            assertEquals("2.9.1", applied(player, TAG));
        }
    }

    @Test
    void replacesAndAddsWhatChangedAndBacksUpTheOldFiles(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of(
                    "mods/a.jar", TestFiles.bytes("A"),
                    "mods/b.jar", TestFiles.bytes("B"),
                    "config/x.cfg", TestFiles.bytes("X")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Update.run(player, TAG, null, false, log);
            FileTime untouched = Files.getLastModifiedTime(player.resolve("config/x.cfg"));

            publish(publisher, TAG, "2.9.1", Map.of(
                    "mods/a.jar", TestFiles.bytes("A2"),
                    "mods/c.jar", TestFiles.bytes("C"),
                    "config/x.cfg", TestFiles.bytes("X")));
            resetLog();
            Update.run(player, TAG, null, false, log);

            assertEquals("A2", Files.readString(player.resolve("mods/a.jar")));
            assertEquals("C", Files.readString(player.resolve("mods/c.jar")));
            assertFalse(Files.exists(player.resolve("mods/b.jar")));
            assertEquals(untouched, Files.getLastModifiedTime(player.resolve("config/x.cfg")),
                    "a file whose hash did not change is not rewritten");
            Path backup = Layout.backupDir(player, TAG, "2.9.0");
            assertEquals("A", Files.readString(backup.resolve("mods/a.jar")));
            assertEquals("B", Files.readString(backup.resolve("mods/b.jar")));
            assertEquals("2.9.1", applied(player, TAG));
            assertTrue(sink.outText().contains("add      mods/c.jar"), sink.outText());
            assertTrue(sink.outText().contains("replace  mods/a.jar"), sink.outText());
            assertTrue(sink.outText().contains("delete   mods/b.jar"), sink.outText());
        }
    }

    @Test
    void leavesFilesAloneWhenOnlyTheirLocalCopyChanged(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of(
                    "config/x.cfg", TestFiles.bytes("X"),
                    "mods/b.jar", TestFiles.bytes("B")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Update.run(player, TAG, null, false, log);

            TestFiles.write(player, "config/x.cfg", "my own settings");
            Files.delete(player.resolve("mods/b.jar"));

            publish(publisher, TAG, "2.9.1", Map.of(
                    "config/x.cfg", TestFiles.bytes("X"),
                    "mods/b.jar", TestFiles.bytes("B"),
                    "mods/c.jar", TestFiles.bytes("C")));
            Update.run(player, TAG, null, false, log);

            assertEquals("my own settings", Files.readString(player.resolve("config/x.cfg")));
            assertFalse(Files.exists(player.resolve("mods/b.jar")), "a file the player removed is not put back");
            assertEquals("C", Files.readString(player.resolve("mods/c.jar")));
        }
    }

    @Test
    void fallsBackToAFullDownloadWhenTheReplacedFileHasNoLocalCopy(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] before = TestFiles.random(300_000, 41);
            byte[] after = before.clone();
            after[120_000] ^= 0x33;
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", before));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Update.run(player, TAG, null, false, log);
            long already = s3.downloadedBytes();

            Files.delete(player.resolve("mods/a.jar"));
            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", after));
            resetLog();
            Update.run(player, TAG, null, false, log);
            long delta = s3.downloadedBytes() - already;

            assertArrayEquals(after, Files.readAllBytes(player.resolve("mods/a.jar")));
            assertEquals(0, reportedReuse(sink.outText()));
            assertTrue(sink.outText().contains("  reuse     0 chunks"), sink.outText());
            assertEquals(after.length + metadataBytes(s3, TAG, "2.9.1", List.of("mods/a.jar")), delta,
                    "with no local copy every chunk is fetched again");
            assertEquals("2.9.1", applied(player, TAG));
        }
    }

    @Test
    void fallsBackToAFullDownloadWhenTheBackupCopyCannotBeRead(@TempDir Path root) throws IOException {
        assumeTrue(Path.of(".").getFileSystem().supportedFileAttributeViews().contains("posix"),
                "the platform has no POSIX file permissions");
        try (FakeS3 s3 = new FakeS3()) {
            byte[] before = TestFiles.random(300_000, 45);
            byte[] after = before.clone();
            after[80_000] ^= 0x44;
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", before));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Update.run(player, TAG, null, false, log);
            long already = s3.downloadedBytes();

            Path local = player.resolve("mods/a.jar");
            Files.setPosixFilePermissions(local, PosixFilePermissions.fromString("---------"));
            assumeFalse(Files.isReadable(local), "this user can read the backup copy anyway");
            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", after));
            resetLog();
            Update.run(player, TAG, null, false, log);
            long delta = s3.downloadedBytes() - already;

            assertArrayEquals(after, Files.readAllBytes(player.resolve("mods/a.jar")));
            assertEquals(0, reportedReuse(sink.outText()),
                    "a backup copy that cannot be read is as good as no copy");
            assertEquals(after.length + metadataBytes(s3, TAG, "2.9.1", List.of("mods/a.jar")), delta,
                    "the unreadable copy forced a full download, not a failure");
        }
    }

    @Test
    void refusesAFileWhoseChunksDoNotAssembleIntoTheManifestHash(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] before = TestFiles.random(300_000, 51);
            byte[] after = before.clone();
            after[100_000] ^= 0x11;
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", before));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Update.run(player, TAG, null, false, log);

            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", after));
            List<String> missing = newHashes(cuts(root, after), hashes(cuts(root, before)));
            assertFalse(missing.isEmpty(), "the changed content has a chunk of its own");
            s3.put(chunkKey(missing.get(0)), TestFiles.bytes("not the chunk it is named after"));

            UserError error = assertThrows(UserError.class,
                    () -> Update.run(player, TAG, null, false, log));

            assertTrue(error.getMessage().contains("manifest"), error.getMessage());
            assertFalse(Files.exists(player.resolve("mods/a.jar")),
                    "a failed assembly leaves no half-written target");
            try (var files = Files.list(player.resolve("mods"))) {
                assertEquals(List.of(), files.toList(), "no temporary file is left behind");
            }
            assertEquals("2.9.0", applied(player, TAG));
        }
    }

    @Test
    void refusesAManifestItCannotRead(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", TestFiles.bytes("A")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Update.run(player, TAG, null, false, log);
            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", TestFiles.bytes("A2")));
            s3.fail("GET", releaseKey(TAG, "2.9.1"), 500);
            int requestsBefore = s3.requests().size();

            UserError error = assertThrows(UserError.class,
                    () -> Update.run(player, TAG, null, false, log));

            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
            assertEquals("A", Files.readString(player.resolve("mods/a.jar")),
                    "a run that cannot read the target manifest touches no file");
            assertEquals("2.9.0", applied(player, TAG));
            assertNothingStaged(player);
            List<String> requests = s3.requests().subList(requestsBefore, s3.requests().size());
            assertTrue(requests.stream().noneMatch(request -> request.startsWith("GET " + TestWorkspace.bucketPath(
                    ObjectKeys.BLOB + "/"))), requests.toString());
        }
    }

    @Test
    void refusesAChunkItCannotDownloadAndLeavesTheWorkspaceRecoverable(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", TestFiles.bytes("A")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Update.run(player, TAG, null, false, log);
            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", TestFiles.bytes("A2")));
            String missing = Sha256.ofBytes(TestFiles.bytes("A2"));
            s3.fail("GET", chunkKey(missing), 500);

            UserError error = assertThrows(UserError.class,
                    () -> Update.run(player, TAG, null, false, log));

            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
            assertEquals("2.9.0", applied(player, TAG), "the pointer did not move");
            assertEquals("A", Files.readString(
                    Layout.backupDir(player, TAG, "2.9.0").resolve("mods/a.jar")),
                    "the file being replaced is kept, not lost");
            try (var walk = Files.walk(player)) {
                List<Path> leftovers = walk.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().startsWith(".update-")
                                || file.getFileName().toString().endsWith(".part"))
                        .toList();
                assertEquals(List.of(), leftovers, "no half-written file is left behind");
            }
            assertNothingStaged(player);
        }
    }

    @Test
    void dryRunFetchesNoChunksAndWritesNothing(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] before = TestFiles.random(300_000, 61);
            byte[] after = before.clone();
            after[150_000] ^= 0x02;
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", before));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Update.run(player, TAG, null, false, log);

            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", after));
            long already = s3.downloadedBytes();
            int requestsBefore = s3.requests().size();
            int logsBefore = logs(player).size();
            resetLog();

            Update.run(player, TAG, null, true, log);

            List<String> requests = s3.requests().subList(requestsBefore, s3.requests().size());
            assertTrue(requests.stream().noneMatch(request ->
                    request.startsWith("GET " + TestWorkspace.bucketPath(ObjectKeys.BLOB + "/"))
                            || request.startsWith("GET " + TestWorkspace.bucketPath(ObjectKeys.INDEX + "/"))),
                    requests.toString());
            assertEquals(s3.object(latestKey(TAG)).length + s3.object(releaseKey(TAG, "2.9.1")).length,
                    s3.downloadedBytes() - already, "a dry run fetches no chunk");
            assertArrayEquals(before, Files.readAllBytes(player.resolve("mods/a.jar")));
            assertEquals("2.9.0", applied(player, TAG));
            assertEquals(logsBefore, logs(player).size(), "a dry run writes no transcript");
            assertTrue(sink.outText().contains("dry run"), sink.outText());
            assertTrue(sink.outText().contains("to download"), sink.outText());
        }
    }

    @Test
    void reportsAPointerWithoutItsManifest(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", TestFiles.bytes("A")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Update.run(player, TAG, null, false, log);
            Files.delete(Layout.releaseFile(player, TAG, "2.9.0"));

            UserError error = assertThrows(UserError.class,
                    () -> Update.run(player, TAG, null, false, log));
            assertTrue(error.getMessage().contains("is missing"), error.getMessage());
        }
    }

    @Test
    void reportsATagThatWasNeverPublished(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);

            UserError error = assertThrows(UserError.class,
                    () -> Update.run(player, TAG, null, false, log));
            assertTrue(error.getMessage().contains("not found"), error.getMessage());
        }
    }

    @Test
    void reusesTheKeptCopiesOfTheReleaseItRollsBackTo(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] before = TestFiles.random(300_000, 85);
            byte[] after = before.clone();
            after[90_000] ^= 0x13;
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", before));
            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", after));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);

            Update.run(player, TAG, "2.9.0", false, log);
            Update.run(player, TAG, null, false, log);
            assertEquals("2.9.1", applied(player, TAG));
            long forwarded = s3.downloadedBytes();

            resetLog();
            Update.run(player, TAG, "2.9.0", false, log);
            long delta = s3.downloadedBytes() - forwarded;

            assertEquals("2.9.0", applied(player, TAG));
            assertArrayEquals(before, Files.readAllBytes(player.resolve("mods/a.jar")));
            assertEquals(hashes(cuts(root, before)).size(), reportedReuse(sink.outText()),
                    "every target chunk came from the copy kept under backup/2.9.0: " + sink.outText());
            assertFalse(hasStep(sink.outText(), "download"), sink.outText());
            String table = manifest(s3, TAG, "2.9.0").files().get("mods/a.jar").indexId();
            assertEquals(s3.object(indexKey(table)).length + s3.object(releaseKey(TAG, "2.9.0")).length, delta,
                    "only the chunk table and the manifest came over the wire: " + s3.requests());
        }
    }

    @Test
    void appliesTheReleaseNamedOnTheCommandLineInsteadOfLatest(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of(
                    "mods/a.jar", TestFiles.bytes("A"),
                    "mods/b.jar", TestFiles.bytes("B")));
            publish(publisher, TAG, "2.9.1", Map.of(
                    "mods/a.jar", TestFiles.bytes("A2"),
                    "mods/b.jar", TestFiles.bytes("B")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            resetLog();

            Update.run(player, TAG, "2.9.0", false, log);

            assertEquals("2.9.0", applied(player, TAG), "the named release, not the latest one");
            assertEquals("A", Files.readString(player.resolve("mods/a.jar")));
            assertEquals(0, getCount(s3, latestKey(TAG)), "a pinned update never reads latest: " + s3.requests());

            resetLog();
            Update.run(player, TAG, "2.9.1", false, log);
            assertEquals("2.9.1", applied(player, TAG));
            assertEquals("A2", Files.readString(player.resolve("mods/a.jar")));

            resetLog();
            Update.run(player, TAG, "2.9.0", false, log);
            assertEquals("2.9.0", applied(player, TAG), "the same option rolls the workspace back");
            assertEquals("A", Files.readString(player.resolve("mods/a.jar")));
            assertEquals("A2", Files.readString(
                    Layout.backupDir(player, TAG, "2.9.1").resolve("mods/a.jar")));
            assertEquals(0, getCount(s3, latestKey(TAG)));

            resetLog();
            Update.run(player, TAG, "2.9.0", false, log);
            assertTrue(sink.outText().contains("is already at 2.9.0"), sink.outText());
        }
    }

    @Test
    void keepsEachTagInItsOwnStateDirectory(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "1.0", Map.of("mods/a.jar", TestFiles.bytes("A")));
            publish(publisher, "client-extra", "1.0", Map.of("shaderpacks/s.zip", TestFiles.bytes("S")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);

            Update.run(player, TAG, null, false, log);
            Update.run(player, "client-extra", null, false, log);

            assertEquals("1.0", applied(player, TAG));
            assertEquals("1.0", applied(player, "client-extra"));
            assertEquals("A", Files.readString(player.resolve("mods/a.jar")));
            assertEquals("S", Files.readString(player.resolve("shaderpacks/s.zip")));
        }
    }

    @Test
    void keepsTheTranscriptOfARunThatTransferred(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", TestFiles.bytes("A")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);

            Update.run(player, TAG, null, false, log);

            List<Path> logs = logs(player);
            assertEquals(1, logs.size(), logs.toString());
            assertTrue(logs.get(0).getFileName().toString().endsWith("-update-client.log"),
                    logs.get(0).toString());
            String text = Files.readString(logs.get(0), StandardCharsets.UTF_8);
            assertTrue(text.contains("update client"), text);
            assertTrue(text.contains("latest   client -> 2.9.0"), text);
            assertTrue(text.contains("add      mods/a.jar"), text);
            assertTrue(text.contains("download 1 chunk"), text);
            assertTrue(text.contains("reuse     0 chunks"), text);
            assertTrue(text.contains("done: client is now at 2.9.0"), text);

            resetLog();
            Update.run(player, TAG, null, false, log);
            assertEquals(1, logs(player).size(), "a run with nothing to transfer leaves no second log");
        }
    }

    @Test
    void writesNoTranscriptForADryRun(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", TestFiles.bytes("A")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Update.run(player, TAG, null, false, log);

            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", TestFiles.bytes("A2")));
            Update.run(player, TAG, null, true, log);

            assertEquals(1, logs(player).size(), "a dry run adds no log");
        }
    }

    @Test
    void namesEachStageAndEndsWithASummary(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of(
                    "mods/a.jar", TestFiles.bytes("A"),
                    "mods/b.jar", TestFiles.bytes("B")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            resetLog();

            Update.run(player, TAG, null, false, log);

            List<String> lines = sink.outText().lines().toList();
            int plan = lines.indexOf("plan");
            int apply = lines.indexOf("apply");
            int commit = lines.indexOf("commit");
            assertTrue(plan > 0 && apply > plan && commit > apply, lines.toString());
            assertTrue(lines.get(plan - 1).isEmpty(), "a stage is set off by a blank line: " + lines);
            assertTrue(lines.contains("  version   first update -> 2.9.0"), lines.toString());
            assertTrue(lines.stream().anyMatch(line -> line.startsWith("  reuse")), lines.toString());
            assertTrue(lines.get(lines.size() - 1).startsWith("  log       "), lines.toString());
            int download = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("  download ")) {
                    download = i;
                }
            }
            assertTrue(download > apply && download < commit, "the transfer is reported in apply: " + lines);
        }
    }
}

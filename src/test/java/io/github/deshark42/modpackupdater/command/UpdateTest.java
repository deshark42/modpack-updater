package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.ChunkTable;
import io.github.deshark42.modpackupdater.model.Chunking;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import io.github.deshark42.modpackupdater.model.Pointer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock(TestLogs.RESOURCE)
class UpdateTest {
    private static final String TAG = "client";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC);

    private static void update(Path workspace, String releaseId) {
        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace, "update-" + TAG, CLOCK);
            Update.run(workspace, TAG, releaseId, CLOCK);
        }
    }

    private static void publish(Path workspace, String releaseId, Map<String, byte[]> files) throws IOException {
        Path base = Layout.baseDir(workspace, TAG);
        if (Files.exists(base)) {
            try (var walk = Files.walk(base)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        for (var file : files.entrySet()) {
            TestFiles.write(base, file.getKey(), file.getValue());
        }
        Publish.run(workspace, TAG, releaseId, Publish.DEFAULT_CACHE_MAX_AGE_DAYS, CLOCK);
    }

    private static String key(String objectKey) {
        return TestWorkspace.bucketPath(objectKey);
    }

    private static Manifest manifest(FakeS3 s3, String releaseId) {
        return Manifest.parse(s3.text(key(ObjectKeys.release(TAG, releaseId))), "published " + releaseId);
    }

    private static ChunkTable table(FakeS3 s3, Manifest manifest, String path) {
        String key = key(ObjectKeys.index(manifest.files().get(path).indexId()));
        return ChunkTable.parse(s3.text(key), key);
    }

    private static void assertRequests(FakeS3 s3, int before, List<String> expected) {
        assertEquals(expected.stream().sorted().toList(),
                s3.requests().subList(before, s3.requests().size()).stream().sorted().toList(),
                "parallel transfers may arrive in either order, but each expected object is read exactly once");
    }

    private static void assertNothingStaged(Path workspace) throws IOException {
        Path temp = Layout.tempDir(workspace);
        if (Files.exists(temp)) {
            try (var walk = Files.walk(temp)) {
                assertEquals(List.of(), walk.filter(Files::isRegularFile).toList());
            }
        }
    }

    private static void oldBackups(Path workspace) throws IOException {
        for (int i = 0; i < 8; i++) {
            Path backup = Layout.backupDir(workspace, TAG, "old" + i);
            TestFiles.write(backup, "mods/old.jar", "backup " + i);
            Files.setLastModifiedTime(backup, FileTime.fromMillis(1_600_000_000_000L + i * 1000L));
        }
    }

    @Test
    void aSuccessfulUpdateCleansOldBackups(@TempDir Path root) throws IOException {
        successfulCleanup(root, "2");
    }

    @Test
    void anAlreadyCurrentCheckCleansOldBackups(@TempDir Path root) throws IOException {
        successfulCleanup(root, "1");
    }

    private static void successfulCleanup(Path root, String target) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, "1", Map.of("mods/a.jar", TestFiles.bytes("old payload")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            TestFiles.write(player, "mods/a.jar", "original local file");
            update(player, "1");
            oldBackups(player);
            publish(publisher, "2", Map.of("mods/a.jar", TestFiles.bytes("new payload")));

            update(player, target);

            assertEquals(target, Pointer.read(Layout.currentFile(player, TAG)).releaseId());
            assertEquals(target.equals("1") ? "old payload" : "new payload",
                    Files.readString(player.resolve("mods/a.jar")));
            assertFalse(Files.exists(Layout.backupDir(player, TAG, "old0")));
            assertEquals(target.equals("1"), Files.exists(Layout.backupDir(player, TAG, "old1")));
            try (var children = Files.list(Layout.backupsDir(player, TAG))) {
                assertEquals(7, children.filter(Files::isDirectory)
                        .filter(path -> !path.getFileName().toString().equals(Layout.INITIAL)).count());
            }
            assertEquals("original local file", Files.readString(Layout.initialBackupDir(player, TAG).resolve("mods/a.jar")));
            if (target.equals("2")) {
                assertEquals("old payload", Files.readString(Layout.backupDir(player, TAG, "1").resolve("mods/a.jar")));
            }
        }
    }

    @Test
    void aFailedStateCommitKeepsBackupsAndTheOldPointer(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, "1", Map.of("mods/a.jar", TestFiles.bytes("old payload")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, "1");
            oldBackups(player);
            publish(publisher, "2", Map.of("mods/a.jar", TestFiles.bytes("new payload")));
            Files.createDirectory(Layout.releaseFile(player, TAG, "2"));

            UserError error = assertThrows(UserError.class, () -> update(player, "2"));

            assertTrue(error.getMessage().contains("cannot write"), error.getMessage());
            assertEquals("1", Pointer.read(Layout.currentFile(player, TAG)).releaseId());
            assertEquals("new payload", Files.readString(player.resolve("mods/a.jar")));
            assertEquals("old payload", Files.readString(Layout.backupDir(player, TAG, "1").resolve("mods/a.jar")));
            for (int i = 0; i < 8; i++) {
                assertEquals("backup " + i, Files.readString(Layout.backupDir(player, TAG, "old" + i).resolve("mods/old.jar")));
            }
            assertNothingStaged(player);
        }
    }

    @Test
    void anOrderWriteFailureStopsBeforeApplicationAndARetryPreservesRefreshedBackups(@TempDir Path root)
            throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, "1", Map.of("mods/a.jar", TestFiles.bytes("old payload")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, "1");
            TestFiles.write(Layout.backupDir(player, TAG, "1"), "mods/a.jar", "older backup");
            String order = "1\n";
            for (int i = 1; i <= 6; i++) {
                TestFiles.write(Layout.backupDir(player, TAG, "old" + i), "mods/a.jar", "backup " + i);
                order += "old" + i + "\n";
            }
            Path orderFile = Layout.backupOrderFile(player, TAG);
            Files.createDirectory(orderFile);
            publish(publisher, "2", Map.of("mods/a.jar", TestFiles.bytes("new payload")));

            UserError error = assertThrows(UserError.class, () -> update(player, "2"));

            assertTrue(error.getMessage().contains("backup order"), error.getMessage());
            assertEquals("old payload", Files.readString(player.resolve("mods/a.jar")));
            assertEquals("older backup", Files.readString(Layout.backupDir(player, TAG, "1").resolve("mods/a.jar")));
            assertEquals("1", Pointer.read(Layout.currentFile(player, TAG)).releaseId());
            assertFalse(Files.exists(Layout.releaseFile(player, TAG, "2")));
            assertNothingStaged(player);
            Files.delete(orderFile);
            Files.writeString(orderFile, order);
            update(player, "2");
            publish(publisher, "3", Map.of("mods/a.jar", TestFiles.bytes("newest payload")));

            update(player, "3");

            assertEquals("3", Pointer.read(Layout.currentFile(player, TAG)).releaseId());
            assertEquals("newest payload", Files.readString(player.resolve("mods/a.jar")));
            assertEquals("old payload", Files.readString(Layout.backupDir(player, TAG, "1").resolve("mods/a.jar")));
            assertEquals("new payload", Files.readString(Layout.backupDir(player, TAG, "2").resolve("mods/a.jar")));
            assertFalse(Files.exists(Layout.backupDir(player, TAG, "old1")));
        }
    }

    @Test
    void aLatestUpdateAppliesTheReleaseWithOnlyThePublicKeyAndPreservesLocalContent(@TempDir Path root)
            throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] renamed = TestFiles.random(150_000, 21);
            byte[] oldPayload = TestFiles.random(Chunking.DEFAULT.max() + 2 * Chunking.DEFAULT.avg(), 22);
            byte[] newPayload = oldPayload.clone();
            newPayload[0] ^= 1;
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, "1", Map.of(
                    "mods/old.jar", renamed,
                    "mods/replace.jar", oldPayload,
                    "mods/remove.jar", TestFiles.bytes("removed payload"),
                    "mods/missing.jar", TestFiles.bytes("old missing payload"),
                    "config/local.cfg", TestFiles.bytes("release defaults"),
                    "config/deleted.cfg", TestFiles.bytes("release defaults"),
                    "config/ready.cfg", TestFiles.bytes("old settings"),
                    "convert", TestFiles.bytes("old tracked file")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3.url(), s3.url() + "/" + TestWorkspace.BUCKET);
            s3.allowAnonymous(true);
            update(player, "1");
            TestFiles.write(player, "config/local.cfg", "player settings");
            Files.delete(player.resolve("config/deleted.cfg"));
            Files.delete(player.resolve("mods/missing.jar"));
            TestFiles.write(player, "config/ready.cfg", "target settings");
            FileTime fixedTime = FileTime.fromMillis(1_600_000_000_000L);
            Files.setLastModifiedTime(player.resolve("config/ready.cfg"), fixedTime);
            Files.delete(player.resolve("convert"));
            TestFiles.write(player, "convert/new.cfg", "matching nested target");
            TestFiles.write(player, "convert/player.cfg", "untracked nested file");
            TestFiles.write(player, "directory/old.cfg", "untracked directory content");
            TestFiles.write(player, "untracked.txt", "leave alone");
            Files.delete(player.resolve(Layout.DEFAULT_PRIVATE_KEY));
            Map<String, byte[]> targetFiles = Map.ofEntries(
                    Map.entry("mods/new.jar", renamed),
                    Map.entry("mods/copy.jar", renamed),
                    Map.entry("mods/replace.jar", newPayload),
                    Map.entry("mods/missing.jar", TestFiles.bytes("new missing payload")),
                    Map.entry("config/local.cfg", TestFiles.bytes("release defaults")),
                    Map.entry("config/deleted.cfg", TestFiles.bytes("release defaults")),
                    Map.entry("config/ready.cfg", TestFiles.bytes("target settings")),
                    Map.entry("convert/new.cfg", TestFiles.bytes("matching nested target")),
                    Map.entry("directory", TestFiles.bytes("now a file")),
                    Map.entry("config/empty.cfg", new byte[0]));
            publish(publisher, "2", targetFiles);
            Manifest previous = manifest(s3, "1");
            Manifest target = manifest(s3, "2");
            Set<String> oldChunks = new TreeSet<>(table(s3, previous, "mods/replace.jar").chunks());
            Set<String> reusedChunks = new TreeSet<>(table(s3, target, "mods/replace.jar").chunks());
            reusedChunks.retainAll(oldChunks);
            Set<String> missingChunks = new TreeSet<>(table(s3, target, "mods/replace.jar").chunks());
            missingChunks.removeAll(oldChunks);
            assertFalse(reusedChunks.isEmpty(), "the replacement fixture includes local chunks at nonzero offsets");
            assertFalse(missingChunks.isEmpty(), "the replacement fixture also requires a remote chunk");
            List<String> fetchedPaths = List.of("mods/new.jar", "mods/copy.jar", "mods/replace.jar",
                    "mods/missing.jar", "convert/new.cfg", "directory", "config/empty.cfg");
            Set<String> tables = new TreeSet<>();
            Set<String> chunks = new TreeSet<>();
            for (String path : fetchedPaths) {
                tables.add(target.files().get(path).indexId());
                chunks.addAll(table(s3, target, path).chunks());
            }
            chunks.removeAll(table(s3, target, "mods/new.jar").chunks());
            chunks.removeAll(oldChunks);
            chunks.removeAll(table(s3, target, "convert/new.cfg").chunks());
            List<String> expected = new ArrayList<>(List.of("GET " + key(ObjectKeys.latest(TAG)),
                    "GET " + key(ObjectKeys.release(TAG, "2")), "GET " + key(ObjectKeys.signature(TAG, "2"))));
            tables.forEach(id -> expected.add("GET " + key(ObjectKeys.index(id))));
            chunks.forEach(hash -> expected.add("GET " + key(ObjectKeys.blob(hash))));
            int before = s3.requests().size();

            update(player, null);

            for (String path : fetchedPaths) {
                assertArrayEquals(targetFiles.get(path), Files.readAllBytes(player.resolve(path)), path);
            }
            assertEquals("player settings", Files.readString(player.resolve("config/local.cfg")));
            assertFalse(Files.exists(player.resolve("config/deleted.cfg")));
            assertFalse(Files.exists(player.resolve("mods/old.jar")));
            assertFalse(Files.exists(player.resolve("mods/remove.jar")));
            assertEquals("target settings", Files.readString(player.resolve("config/ready.cfg")));
            assertEquals(fixedTime, Files.getLastModifiedTime(player.resolve("config/ready.cfg")));
            assertEquals("leave alone", Files.readString(player.resolve("untracked.txt")));
            assertFalse(Files.exists(player.resolve("convert/player.cfg")));
            Path backup = Layout.backupDir(player, TAG, "1");
            assertArrayEquals(renamed, Files.readAllBytes(backup.resolve("mods/old.jar")));
            assertArrayEquals(oldPayload, Files.readAllBytes(backup.resolve("mods/replace.jar")));
            assertEquals("removed payload", Files.readString(backup.resolve("mods/remove.jar")));
            assertEquals("untracked nested file", Files.readString(backup.resolve("convert/player.cfg")));
            assertEquals("untracked directory content", Files.readString(backup.resolve("directory/old.cfg")));
            assertFalse(Files.exists(backup.resolve("config/ready.cfg")));
            assertEquals("2", Pointer.read(Layout.currentFile(player, TAG)).releaseId());
            assertEquals(target, Manifest.read(Layout.releaseFile(player, TAG, "2")));
            assertFalse(Files.exists(player.resolve(Layout.DEFAULT_PRIVATE_KEY)));
            assertRequests(s3, before, expected);
            for (var request : s3.requestsDetailed().subList(before, s3.requestsDetailed().size())) {
                assertFalse(request.headers().containsKey("authorization"), "subscriber reads are anonymous");
            }
            assertNothingStaged(player);
        }
    }

    @Test
    void aTamperedLatestReleaseLeavesTheAppliedReleaseAndPayloadUntouched(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, "1", Map.of("mods/a.jar", TestFiles.bytes("old payload")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, "1");
            byte[] localManifest = Files.readAllBytes(Layout.releaseFile(player, TAG, "1"));
            publish(publisher, "2", Map.of("mods/a.jar", TestFiles.bytes("new payload")));
            s3.put(key(ObjectKeys.release(TAG, "2")), TestFiles.bytes("tampered manifest"));
            int before = s3.requests().size();

            UserError error = assertThrows(UserError.class, () -> update(player, null));

            assertTrue(error.getMessage().contains("signature"), error.getMessage());
            assertEquals("old payload", Files.readString(player.resolve("mods/a.jar")));
            assertEquals("1", Pointer.read(Layout.currentFile(player, TAG)).releaseId());
            assertArrayEquals(localManifest, Files.readAllBytes(Layout.releaseFile(player, TAG, "1")));
            assertFalse(Files.exists(Layout.releaseFile(player, TAG, "2")));
            assertFalse(Files.exists(Layout.backupsDir(player, TAG)));
            assertRequests(s3, before, List.of("GET " + key(ObjectKeys.latest(TAG)),
                    "GET " + key(ObjectKeys.release(TAG, "2")), "GET " + key(ObjectKeys.signature(TAG, "2"))));
            assertNothingStaged(player);
        }
    }

    @Test
    void anExplicitReleaseRollsBackFromKeptCopiesWithoutReadingLatestOrDownloadingChunks(@TempDir Path root)
            throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            byte[] original = TestFiles.random(150_000, 85);
            publish(publisher, "1", Map.of("mods/a.jar", original));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, "1");
            publish(publisher, "2", Map.of("mods/a.jar", TestFiles.bytes("new payload")));
            update(player, "2");
            Manifest target = manifest(s3, "1");
            int before = s3.requests().size();

            update(player, "1");

            assertArrayEquals(original, Files.readAllBytes(player.resolve("mods/a.jar")));
            assertEquals("1", Pointer.read(Layout.currentFile(player, TAG)).releaseId());
            assertEquals(target, Manifest.read(Layout.releaseFile(player, TAG, "1")));
            assertEquals("new payload", Files.readString(Layout.backupDir(player, TAG, "2").resolve("mods/a.jar")));
            assertRequests(s3, before, List.of("GET " + key(ObjectKeys.release(TAG, "1")),
                    "GET " + key(ObjectKeys.signature(TAG, "1")),
                    "GET " + key(ObjectKeys.index(target.files().get("mods/a.jar").indexId()))));
            assertNothingStaged(player);
        }
    }

    @Test
    void aCorruptExplicitReleaseCannotReplaceFilesOrAdvanceTheLocalPointer(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, "1", Map.of("mods/a.jar", TestFiles.bytes("old payload")));
            Manifest target = manifest(s3, "1");
            publish(publisher, "2", Map.of("mods/a.jar", TestFiles.bytes("new payload")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, "2");
            String hash = table(s3, target, "mods/a.jar").chunks().get(0);
            s3.put(key(ObjectKeys.blob(hash)), TestFiles.bytes("corrupt payload"));
            byte[] localManifest = Files.readAllBytes(Layout.releaseFile(player, TAG, "2"));
            int before = s3.requests().size();

            UserError error = assertThrows(UserError.class, () -> update(player, "1"));

            assertTrue(error.getMessage().contains("manifest records"), error.getMessage());
            assertEquals("new payload", Files.readString(player.resolve("mods/a.jar")));
            assertEquals("2", Pointer.read(Layout.currentFile(player, TAG)).releaseId());
            assertArrayEquals(localManifest, Files.readAllBytes(Layout.releaseFile(player, TAG, "2")));
            assertFalse(Files.exists(Layout.releaseFile(player, TAG, "1")));
            assertFalse(Files.exists(Layout.backupsDir(player, TAG)));
            assertRequests(s3, before, List.of("GET " + key(ObjectKeys.release(TAG, "1")),
                    "GET " + key(ObjectKeys.signature(TAG, "1")),
                    "GET " + key(ObjectKeys.index(target.files().get("mods/a.jar").indexId())),
                    "GET " + key(ObjectKeys.blob(hash))));
            assertNothingStaged(player);
        }
    }
}

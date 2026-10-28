package io.github.deshark42.modpackupdater.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ResourceLock(TestLogs.RESOURCE)
class BackupRetentionTest {
    private static final String TAG = "client";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC);

    private static Path backup(Path workspace, String tag, String id, int time) throws IOException {
        Path directory = Layout.backupDir(workspace, tag, id);
        TestFiles.write(directory, "nested/payload", id);
        Files.setLastModifiedTime(directory, FileTime.fromMillis(1_600_000_000_000L + time * 1000L));
        return directory;
    }

    private static void history(Path workspace, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            backup(workspace, TAG, "r" + i, i);
        }
    }

    private static Set<String> directories(Path workspace, String tag) throws IOException {
        try (var children = Files.list(Layout.backupsDir(workspace, tag))) {
            return children.filter(Files::isDirectory).map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }

    @ParameterizedTest(name = "{0} nonempty backups retain at most seven")
    @ValueSource(ints = {1, 6, 7, 8, 10})
    void keepsTheNewestSevenNonemptyBackups(int count, @TempDir Path workspace) throws IOException {
        history(workspace, count);

        BackupRetention.afterUpdate(workspace, TAG, null);

        assertEquals(IntStream.range(Math.max(0, count - 7), count).mapToObj(i -> "r" + i)
                .collect(Collectors.toSet()), directories(workspace, TAG));
        for (int i = Math.max(0, count - 7); i < count; i++) {
            assertEquals("r" + i, Files.readString(Layout.backupDir(workspace, TAG, "r" + i).resolve("nested/payload")));
        }
    }

    @Test
    void aMissingBackupDirectoryCreatesNoState(@TempDir Path workspace) {
        BackupRetention.afterUpdate(workspace, TAG, null);

        assertFalse(Files.exists(Layout.stateDir(workspace)));
    }

    @Test
    void ignoresInitialEmptyAndUnrelatedEntriesAndCountsZeroByteFiles(@TempDir Path workspace) throws IOException {
        history(workspace, 7);
        Path initial = Layout.initialBackupDir(workspace, TAG);
        TestFiles.write(initial, "old.cfg", "original instance");
        Files.createDirectories(Layout.backupDir(workspace, TAG, "empty").resolve("nested/empty"));
        Path zero = Layout.backupDir(workspace, TAG, "zero");
        TestFiles.write(zero, "empty.cfg", new byte[0]);
        TestFiles.write(Layout.backupsDir(workspace, TAG), "readme.txt", "not a backup directory");
        TestFiles.write(Layout.backupsDir(workspace, TAG).resolve(".manual"), "file", "leave alone");
        history(workspace.resolve("other-workspace"), 8);
        backup(workspace, "server", "untouched", 0);

        BackupRetention.afterUpdate(workspace, TAG, "zero");

        assertEquals(Set.of("r1", "r2", "r3", "r4", "r5", "r6", "zero", "empty", Layout.INITIAL, ".manual"),
                directories(workspace, TAG));
        assertEquals("original instance", Files.readString(initial.resolve("old.cfg")));
        assertEquals(0, Files.size(zero.resolve("empty.cfg")));
        assertEquals("not a backup directory", Files.readString(Layout.backupsDir(workspace, TAG).resolve("readme.txt")));
        assertEquals("leave alone", Files.readString(Layout.backupsDir(workspace, TAG).resolve(".manual/file")));
        assertEquals("untouched", Files.readString(Layout.backupDir(workspace, "server", "untouched").resolve("nested/payload")));
        assertEquals(8, directories(workspace.resolve("other-workspace"), TAG).size());
    }

    @Test
    void recordedOrderSurvivesDirectoryTimeChangesAndAnOldReleaseGetsRefreshed(@TempDir Path workspace)
            throws IOException {
        history(workspace, 7);
        BackupRetention.afterUpdate(workspace, TAG, null);
        Files.setLastModifiedTime(Layout.backupDir(workspace, TAG, "r0"), FileTime.fromMillis(4_000_000_000_000L));
        backup(workspace, TAG, "r7", -1);
        BackupRetention.afterUpdate(workspace, TAG, "r1");

        assertEquals(Set.of("r0", "r1", "r2", "r3", "r4", "r5", "r6"), directories(workspace, TAG));
        assertEquals(List.of("r0", "r2", "r3", "r4", "r5", "r6", "r1"),
                Files.readAllLines(Layout.backupOrderFile(workspace, TAG)).stream().filter(id -> !id.equals("r7")).toList());
    }

    @Test
    void aNewBackupIsNewestRegardlessOfItsNameOrDirectoryTime(@TempDir Path workspace) throws IOException {
        history(workspace, 7);
        BackupRetention.afterUpdate(workspace, TAG, null);
        Files.setLastModifiedTime(Layout.backupDir(workspace, TAG, "r0"), FileTime.fromMillis(4_000_000_000_000L));
        backup(workspace, TAG, "aaa", -1);

        BackupRetention.afterUpdate(workspace, TAG, "aaa");

        assertEquals(Set.of("r1", "r2", "r3", "r4", "r5", "r6", "aaa"), directories(workspace, TAG));
    }

    @ParameterizedTest(name = "invalid order: {0}")
    @ValueSource(strings = {"../outside\n", ".initial\n", "r1\nr1\n"})
    void anInvalidOrderPreservesEveryBackup(String content, @TempDir Path workspace) throws IOException {
        history(workspace, 8);
        Files.writeString(Layout.backupOrderFile(workspace, TAG), content);
        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace, "retention", CLOCK);

            BackupRetention.afterUpdate(workspace, TAG, null);

            assertTrue(logs.errText().contains("could not clean backups"), logs.errText());
        }
        assertEquals(8, directories(workspace, TAG).size());
        assertEquals(content, Files.readString(Layout.backupOrderFile(workspace, TAG)));
    }

    @Test
    void anUnreadableOrderPreservesEveryBackup(@TempDir Path workspace) throws IOException {
        history(workspace, 8);
        Files.createDirectory(Layout.backupOrderFile(workspace, TAG));
        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace, "retention", CLOCK);

            BackupRetention.afterUpdate(workspace, TAG, null);

            assertTrue(logs.errText().contains("could not clean backups"), logs.errText());
        }
        assertEquals(8, directories(workspace, TAG).size());
    }

    @Test
    void recordingAnInterruptedBackupPreservesItsRecencyWithoutDeletingAnything(@TempDir Path workspace)
            throws IOException {
        history(workspace, 8);
        BackupRetention.record(workspace, TAG, "r0");
        assertEquals(8, directories(workspace, TAG).size());
        backup(workspace, TAG, "new", -1);
        BackupRetention.record(workspace, TAG, "new");

        BackupRetention.afterUpdate(workspace, TAG, null);

        assertEquals(Set.of("r0", "r3", "r4", "r5", "r6", "r7", "new"), directories(workspace, TAG));
        assertEquals("r0", Files.readString(Layout.backupDir(workspace, TAG, "r0").resolve("nested/payload")));
    }

    @Test
    void staleOrderEntriesDisappearWhenBackupsAreManuallyRemoved(@TempDir Path workspace) throws IOException {
        history(workspace, 1);
        Files.writeString(Layout.backupOrderFile(workspace, TAG), "missing\nr0\n");

        BackupRetention.afterUpdate(workspace, TAG, null);

        assertEquals(List.of("r0"), Files.readAllLines(Layout.backupOrderFile(workspace, TAG)));
        assertEquals(Set.of("r0"), directories(workspace, TAG));
    }

    @Test
    void equalLegacyTimestampsUseReleaseNamesOnlyAsATieBreaker(@TempDir Path workspace) throws IOException {
        for (String id : List.of("z", "g", "f", "e", "d", "c", "b", "a")) {
            backup(workspace, TAG, id, 0);
        }

        BackupRetention.afterUpdate(workspace, TAG, null);

        assertEquals(Set.of("z", "g", "f", "e", "d", "c", "b"), directories(workspace, TAG));
    }

    @Test
    void aNestedLinkPreventsDeletionWithoutFollowingIt(@TempDir Path workspace) throws IOException {
        history(workspace, 8);
        Path outside = workspace.resolve("outside");
        TestFiles.write(outside, "precious", "external data");
        try {
            Files.createSymbolicLink(Layout.backupDir(workspace, TAG, "r0").resolve("linked"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links require filesystem support and privileges: " + e);
        }
        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace, "retention", CLOCK);

            BackupRetention.afterUpdate(workspace, TAG, null);

            assertTrue(logs.errText().contains("filesystem link or alias"), logs.errText());
        }
        assertEquals(8, directories(workspace, TAG).size());
        assertEquals("external data", Files.readString(outside.resolve("precious")));
        assertFalse(Files.exists(Layout.backupOrderFile(workspace, TAG)));
    }
}

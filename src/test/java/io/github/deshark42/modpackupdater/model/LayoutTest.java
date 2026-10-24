package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LayoutTest {

    private static final Path WORKSPACE = Path.of("/ws");

    @Test
    void namesTheStateDirectoryTheNotesDescribe() {
        assertEquals(".modpack-updater", Layout.STATE_DIR);
        assertEquals("config.json", Layout.CONFIG);
        assertEquals("known-blobs.jsonl", Layout.KNOWN_BLOBS);
        assertEquals("current.json", Layout.CURRENT);
        assertEquals("releases", Layout.RELEASES);
        assertEquals("backup", Layout.BACKUP);
        assertEquals("logs", Layout.LOGS);
        assertEquals("base", Layout.BASE);
        assertEquals("overlay", Layout.OVERLAY);
        assertEquals("remove.txt", Layout.REMOVE);
        assertEquals("tmp", Layout.TMP);
        assertEquals("blob", Layout.BLOB);
        assertEquals("file", Layout.FILE);
        assertEquals(".initial", Layout.INITIAL);
    }

    @Test
    void keepsTheWorkspaceStateUnderOneDirectory() {
        assertEquals(WORKSPACE.resolve(".modpack-updater"), Layout.stateDir(WORKSPACE));
        assertEquals(WORKSPACE.resolve(".modpack-updater/config.json"), Layout.configFile(WORKSPACE));
        assertEquals(WORKSPACE.resolve(".modpack-updater/logs"), Layout.logsDir(WORKSPACE));
        assertEquals(WORKSPACE.resolve(".modpack-updater/tmp"), Layout.tempDir(WORKSPACE));
        assertEquals(WORKSPACE.resolve(".modpack-updater/tmp/blob"), Layout.blobTempDir(WORKSPACE));
        assertEquals(WORKSPACE.resolve(".modpack-updater/tmp/file"), Layout.fileTempDir(WORKSPACE));
        assertEquals(WORKSPACE.resolve(".modpack-updater/known-blobs.jsonl"), Layout.knownBlobsFile(WORKSPACE));
    }

    @Test
    void keepsEachTagsStateInItsOwnDirectory() {
        assertEquals(WORKSPACE.resolve(".modpack-updater/client"), Layout.tagDir(WORKSPACE, "client"));
        assertEquals(WORKSPACE.resolve(".modpack-updater/client/current.json"),
                Layout.currentFile(WORKSPACE, "client"));
        assertEquals(WORKSPACE.resolve(".modpack-updater/client/releases"), Layout.releasesDir(WORKSPACE, "client"));
        assertEquals(WORKSPACE.resolve(".modpack-updater/client/releases/2.9.0.json"),
                Layout.releaseFile(WORKSPACE, "client", "2.9.0"));
        assertEquals(WORKSPACE.resolve(".modpack-updater/client/backup"), Layout.backupsDir(WORKSPACE, "client"));
        assertEquals(WORKSPACE.resolve(".modpack-updater/client/backup/2.9.0"),
                Layout.backupDir(WORKSPACE, "client", "2.9.0"));
        assertEquals(WORKSPACE.resolve(".modpack-updater/client/backup/.initial"),
                Layout.initialBackupDir(WORKSPACE, "client"));
    }

    @Test
    void publishesFromTheTagDirectoryAtTheWorkspaceRoot() {
        assertEquals(WORKSPACE.resolve("client"), Layout.tagRoot(WORKSPACE, "client"));
        assertEquals(WORKSPACE.resolve("client/base"), Layout.baseDir(WORKSPACE, "client"));
        assertEquals(WORKSPACE.resolve("client/overlay"), Layout.overlayDir(WORKSPACE, "client"));
        assertEquals(WORKSPACE.resolve("client/remove.txt"), Layout.removeFile(WORKSPACE, "client"));
    }

    @Test
    void rejectsUnsafeTagsAndReleaseIds() {
        UserError tag = assertThrows(UserError.class, () -> Layout.tagDir(WORKSPACE, "../client"));
        assertTrue(tag.getMessage().contains("invalid tag"), tag.getMessage());
        UserError releaseId = assertThrows(UserError.class,
                () -> Layout.releaseFile(WORKSPACE, "client", "../2.9.0"));
        assertTrue(releaseId.getMessage().contains("invalid release id"), releaseId.getMessage());
        UserError nested = assertThrows(UserError.class, () -> Layout.tagRoot(WORKSPACE, "a/b"));
        assertTrue(nested.getMessage().contains("invalid tag"), nested.getMessage());
    }
}

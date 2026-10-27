package io.github.deshark42.modpackupdater.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.Pointer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock(TestLogs.RESOURCE)
class AppliedReleaseTest {
    private static final String TAG = "client";

    @Test
    void aWorkspaceWithoutACurrentPointerHasNoAppliedRelease(@TempDir Path workspace) throws IOException {
        AppliedRelease applied = AppliedRelease.read(workspace, TAG, "2");

        assertEquals(new AppliedRelease(null, null), applied);
        try (var paths = Files.list(workspace)) {
            assertEquals(List.of(), paths.toList());
        }
    }

    @Test
    void aDifferentTargetReadsTheAppliedManifestWithoutChangingIt(@TempDir Path workspace) throws IOException {
        Manifest manifest = new Manifest(TAG, "1", Instant.EPOCH, Manifest.SHA256, Map.of());
        manifest.write(Layout.releaseFile(workspace, TAG, "1"));
        new Pointer("1").write(Layout.currentFile(workspace, TAG));
        byte[] bytes = Files.readAllBytes(Layout.releaseFile(workspace, TAG, "1"));

        AppliedRelease applied = AppliedRelease.read(workspace, TAG, "2");

        assertEquals(new AppliedRelease("1", manifest), applied);
        assertArrayEquals(bytes, Files.readAllBytes(Layout.releaseFile(workspace, TAG, "1")));
        assertEquals("1", Pointer.read(Layout.currentFile(workspace, TAG)).releaseId());
        assertFalse(Files.exists(Layout.releaseFile(workspace, TAG, "2")));
    }

    @Test
    void anAlreadyAppliedTargetDoesNotParseItsExistingManifest(@TempDir Path workspace) throws IOException {
        new Pointer("1").write(Layout.currentFile(workspace, TAG));
        TestFiles.write(workspace, workspace.relativize(Layout.releaseFile(workspace, TAG, "1")).toString(),
                "legacy manifest that is no longer parseable");
        byte[] before = Files.readAllBytes(Layout.releaseFile(workspace, TAG, "1"));

        AppliedRelease applied = AppliedRelease.read(workspace, TAG, "1");

        assertEquals(new AppliedRelease("1", null), applied);
        assertArrayEquals(before, Files.readAllBytes(Layout.releaseFile(workspace, TAG, "1")));
        assertEquals("1", Pointer.read(Layout.currentFile(workspace, TAG)).releaseId());
    }

    @Test
    void aMissingAppliedManifestFailsBeforeAnAlreadyCurrentTargetCanReturn(@TempDir Path workspace) throws IOException {
        new Pointer("1").write(Layout.currentFile(workspace, TAG));

        UserError error = assertThrows(UserError.class, () -> AppliedRelease.read(workspace, TAG, "1"));

        assertTrue(error.getMessage().contains("cannot tell which files are already applied"), error.getMessage());
        assertTrue(error.getMessage().contains("is missing"), error.getMessage());
        assertEquals("1", Pointer.read(Layout.currentFile(workspace, TAG)).releaseId());
        assertFalse(Files.exists(Layout.releaseFile(workspace, TAG, "1")));
        assertFalse(Files.exists(Layout.backupsDir(workspace, TAG)));
    }

    @Test
    void aMissingAppliedManifestFailsWithoutCreatingTheRequestedRelease(@TempDir Path workspace) throws IOException {
        new Pointer("1").write(Layout.currentFile(workspace, TAG));

        UserError error = assertThrows(UserError.class, () -> AppliedRelease.read(workspace, TAG, "2"));

        assertTrue(error.getMessage().contains("cannot tell which files are already applied"), error.getMessage());
        assertTrue(error.getMessage().contains("is missing"), error.getMessage());
        assertEquals("1", Pointer.read(Layout.currentFile(workspace, TAG)).releaseId());
        assertFalse(Files.exists(Layout.releaseFile(workspace, TAG, "2")));
        assertFalse(Files.exists(Layout.backupsDir(workspace, TAG)));
    }
}

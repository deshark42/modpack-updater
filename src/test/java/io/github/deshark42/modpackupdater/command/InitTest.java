package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.json.Json;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock(TestLogs.RESOURCE)
class InitTest {
    private static final String TAG = "client";

    @Test
    void aWorkspaceGainsMissingPublishInputsWithoutReplacingExistingOnes(@TempDir Path workspace) throws IOException {
        TestWorkspace.base(workspace, TAG, "mods/a.jar", "original base");
        TestWorkspace.overlay(workspace, TAG, "mods/a.jar", "local overlay");
        TestWorkspace.remove(workspace, TAG, "example-mod-*.jar\n");

        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace);

            Init.run(workspace, TAG);

            assertTrue(Files.isDirectory(Layout.basePackDir(workspace, TAG)));
            assertEquals("original base", Files.readString(Layout.baseDir(workspace, TAG).resolve("mods/a.jar")));
            assertEquals("local overlay", Files.readString(Layout.overlayDir(workspace, TAG).resolve("mods/a.jar")));
            assertEquals("example-mod-*.jar\n", Files.readString(Layout.removeFile(workspace, TAG)));
            Map<String, Object> template = Json.asObject(
                    Json.parse(Files.readString(Layout.configFile(workspace))), "config template");
            assertEquals("", template.get("endpoint"));
            assertEquals("", template.get("bucket"));
            assertEquals("", template.get("access_key"));
            assertEquals("", template.get("secret_key"));
            assertEquals(Layout.DEFAULT_PRIVATE_KEY, template.get("signing_private_key"));
            assertEquals(Layout.DEFAULT_PUBLIC_KEY, template.get("trusted_public_key"));
            assertEquals(Config.DEFAULT_CONCURRENCY, ((Number) template.get("concurrency")).intValue());
            assertEquals("", logs.errText());
        }
    }

    @Test
    void aBlockedTagLeavesExistingWorkspaceInputsUntouched(@TempDir Path workspace) throws IOException {
        TestWorkspace.configure(workspace, "https://s3.example.com", null);
        Config.fillMissing(workspace);
        String before = Files.readString(Layout.configFile(workspace));
        TestFiles.write(workspace, TAG, "a file blocks the tag directory");

        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace);

            UserError error = assertThrows(UserError.class, () -> Init.run(workspace, TAG));

            assertTrue(error.getMessage().contains("cannot create"), error.getMessage());
            assertEquals(before, Files.readString(Layout.configFile(workspace)));
            assertEquals("a file blocks the tag directory", Files.readString(workspace.resolve(TAG)));
            assertFalse(Files.exists(workspace.resolve(TAG).resolve(Layout.BASE)));
            assertFalse(Files.exists(workspace.resolve(TAG).resolve(Layout.BASE_PACK)));
            assertFalse(Files.exists(workspace.resolve(TAG).resolve(Layout.OVERLAY)));
            assertFalse(Files.exists(workspace.resolve(TAG).resolve(Layout.REMOVE)));
        }
    }
}

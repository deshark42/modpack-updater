package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.json.Json;
import io.github.deshark42.modpackupdater.log.Logging;
import io.github.deshark42.modpackupdater.log.Text;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitTest {

    private static final String TAG = "client";

    /** Starts a VERBOSE run, runs init in it, and returns what it printed. */
    private static TestLogs init(Path workspace, String tag) {
        TestLogs logs = new TestLogs();
        logs.begin(workspace, Logging.Mode.VERBOSE);
        try {
            Init.run(workspace, tag);
        } finally {
            logs.close();
        }
        return logs;
    }

    /** Whether the run printed one step line with this verb about this path. */
    private static boolean said(TestLogs logs, String verb, Path workspace, Path file) {
        String shown = Text.relativeTo(workspace, file);
        return logs.outText().lines().anyMatch(line -> line.contains(verb + " " + shown));
    }

    private static List<String> rulesIn(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    @Test
    void writesAConfigTemplateAndThePublishInputsOfATag(@TempDir Path workspace) throws IOException {
        TestLogs logs = init(workspace, TAG);

        Path config = Layout.configFile(workspace);
        assertTrue(Files.isRegularFile(config), logs.outText());
        assertTrue(Files.isDirectory(Layout.baseDir(workspace, TAG)), logs.outText());
        assertTrue(Files.isDirectory(Layout.overlayDir(workspace, TAG)), logs.outText());
        assertTrue(Files.isRegularFile(Layout.removeFile(workspace, TAG)), logs.outText());
        assertTrue(said(logs, "created", workspace, config), logs.outText());
        assertTrue(said(logs, "created", workspace, Layout.baseDir(workspace, TAG)), logs.outText());
        assertTrue(said(logs, "created", workspace, Layout.overlayDir(workspace, TAG)), logs.outText());
        assertTrue(said(logs, "created", workspace, Layout.removeFile(workspace, TAG)), logs.outText());
        assertTrue(logs.outText().contains("fill in \"endpoint\" and \"bucket\""), logs.outText());
        assertEquals(List.of(), rulesIn(Layout.removeFile(workspace, TAG)),
                Files.readString(Layout.removeFile(workspace, TAG)));

        // Pointing the template at a bucket is all a config needs to become loadable.
        Map<String, Object> template = Json.asObject(Json.parse(Files.readString(config)), "config template");
        assertEquals("", template.get("endpoint"));
        assertEquals("", template.get("read_endpoint"));
        assertEquals("", template.get("bucket"));
        assertEquals("", template.get("access_key"));
        assertEquals("", template.get("secret_key"));
        template.put("endpoint", "https://s3.example.com");
        template.put("bucket", TestWorkspace.BUCKET);
        Config parsed = Config.parse(Json.write(template), "config.json");
        assertEquals(TestWorkspace.BUCKET, parsed.bucket());
        assertTrue(parsed.anonymous(), "empty keys mean a public bucket");
    }

    @Test
    void keepsWhatIsAlreadyThere(@TempDir Path workspace) throws IOException {
        String endpoint;
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            endpoint = s3.url();
        }
        TestFiles.write(Layout.baseDir(workspace, TAG), "mods/a.jar", TestFiles.bytes("A"));
        TestFiles.write(Layout.overlayDir(workspace, TAG), "mods/a.jar", TestFiles.bytes("mine"));
        TestFiles.write(workspace, TAG + "/" + Layout.REMOVE, "example-mod-*.jar\n");

        TestLogs logs = init(workspace, TAG);

        Config keptConfig = Config.load(workspace);
        assertEquals(endpoint, keptConfig.endpoint(), "the existing config is left alone");
        assertEquals(TestWorkspace.BUCKET, keptConfig.bucket());
        assertEquals(TestWorkspace.ACCESS_KEY, keptConfig.accessKey());
        assertEquals(TestWorkspace.SECRET_KEY, keptConfig.secretKey());
        assertTrue(keptConfig.pathStyle());
        assertEquals("example-mod-*.jar\n", Files.readString(Layout.removeFile(workspace, TAG)));
        assertTrue(said(logs, "kept", workspace, Layout.configFile(workspace)), logs.outText());
        assertTrue(said(logs, "kept", workspace, Layout.baseDir(workspace, TAG)), logs.outText());
        assertTrue(said(logs, "kept", workspace, Layout.overlayDir(workspace, TAG)), logs.outText());
        assertTrue(said(logs, "kept", workspace, Layout.removeFile(workspace, TAG)), logs.outText());
        assertFalse(logs.outText().contains("created"), logs.outText());
        assertEquals("A", Files.readString(Layout.baseDir(workspace, TAG).resolve("mods/a.jar")));
        assertEquals("mine", Files.readString(Layout.overlayDir(workspace, TAG).resolve("mods/a.jar")));
    }

    @Test
    void fillsInTheDefaultsAConfigIsMissing(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
        }

        TestLogs logs = init(workspace, null);

        Config kept = Config.load(workspace);
        assertEquals(Config.DEFAULT_CONCURRENCY, kept.concurrency());
        assertEquals(TestWorkspace.REGION, kept.region());
        assertTrue(kept.pathStyle());
        assertTrue(said(logs, "config", workspace, Layout.configFile(workspace)), logs.outText());
        assertTrue(logs.outText().contains("filled in the defaults"), logs.outText());
    }

    @Test
    void withoutATagOnlyTheConfigIsWritten(@TempDir Path workspace) throws IOException {
        init(workspace, null);

        assertTrue(Files.isRegularFile(Layout.configFile(workspace)));
        try (var entries = Files.list(workspace)) {
            assertEquals(1, entries.count(), "nothing but the state directory is laid out");
        }
    }

    @Test
    void rejectsATagThatWouldEscapeTheWorkspace(@TempDir Path workspace) {
        TestLogs logs = new TestLogs();
        logs.begin(workspace, Logging.Mode.VERBOSE);
        try {
            UserError error = assertThrows(UserError.class, () -> Init.run(workspace, "../client"));

            assertTrue(error.getMessage().contains("invalid tag"), error.getMessage());
            assertFalse(Files.exists(workspace.resolve("client")), "no tag directory escapes the workspace");
        } finally {
            logs.close();
        }
    }
}

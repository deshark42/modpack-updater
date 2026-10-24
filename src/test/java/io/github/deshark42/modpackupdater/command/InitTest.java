package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.LogSink;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.json.Json;
import io.github.deshark42.modpackupdater.log.Log;
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

    private final LogSink sink = new LogSink();
    private final Log log = sink.log();

    /** Whether the run printed one step line with this verb about this path. */
    private boolean said(String verb, Path workspace, Path file) {
        String shown = Log.relativeTo(workspace, file);
        return sink.outText().lines()
                .anyMatch(line -> line.stripLeading().startsWith(verb + " ") && line.contains(shown));
    }

    private static List<String> rulesIn(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    @Test
    void writesAConfigTemplateAndThePublishInputsOfATag(@TempDir Path workspace) throws IOException {
        Init.run(workspace, TAG, log);

        Path config = Layout.configFile(workspace);
        assertTrue(Files.isRegularFile(config), sink.outText());
        assertTrue(Files.isDirectory(Layout.baseDir(workspace, TAG)), sink.outText());
        assertTrue(Files.isDirectory(Layout.overlayDir(workspace, TAG)), sink.outText());
        assertTrue(Files.isRegularFile(Layout.removeFile(workspace, TAG)), sink.outText());
        assertTrue(said("created", workspace, config), sink.outText());
        assertTrue(said("created", workspace, Layout.baseDir(workspace, TAG)), sink.outText());
        assertTrue(said("created", workspace, Layout.overlayDir(workspace, TAG)), sink.outText());
        assertTrue(said("created", workspace, Layout.removeFile(workspace, TAG)), sink.outText());
        assertTrue(sink.outText().contains("fill in \"endpoint\" and \"bucket\""), sink.outText());
        assertEquals(List.of(), rulesIn(Layout.removeFile(workspace, TAG)),
                Files.readString(Layout.removeFile(workspace, TAG)));

        // Pointing the template at a bucket is all a config needs to become loadable.
        Map<String, Object> template = Json.asObject(Json.parse(Files.readString(config)), "config template");
        assertEquals("", template.get("endpoint"));
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

        Init.run(workspace, TAG, log);

        Config keptConfig = Config.load(workspace);
        assertEquals(endpoint, keptConfig.endpoint(), "the existing config is left alone");
        assertEquals(TestWorkspace.BUCKET, keptConfig.bucket());
        assertEquals(TestWorkspace.ACCESS_KEY, keptConfig.accessKey());
        assertEquals(TestWorkspace.SECRET_KEY, keptConfig.secretKey());
        assertTrue(keptConfig.pathStyle());
        assertEquals("example-mod-*.jar\n", Files.readString(Layout.removeFile(workspace, TAG)));
        assertTrue(said("kept", workspace, Layout.configFile(workspace)), sink.outText());
        assertTrue(said("kept", workspace, Layout.baseDir(workspace, TAG)), sink.outText());
        assertTrue(said("kept", workspace, Layout.overlayDir(workspace, TAG)), sink.outText());
        assertTrue(said("kept", workspace, Layout.removeFile(workspace, TAG)), sink.outText());
        assertFalse(sink.outText().contains("created"), sink.outText());
        assertEquals("A", Files.readString(Layout.baseDir(workspace, TAG).resolve("mods/a.jar")));
        assertEquals("mine", Files.readString(Layout.overlayDir(workspace, TAG).resolve("mods/a.jar")));
    }

    @Test
    void fillsInTheDefaultsAConfigIsMissing(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
        }

        Init.run(workspace, null, log);

        Config kept = Config.load(workspace);
        assertEquals(Config.DEFAULT_CONCURRENCY, kept.concurrency());
        assertEquals(TestWorkspace.REGION, kept.region());
        assertTrue(kept.pathStyle());
        assertTrue(said("config", workspace, Layout.configFile(workspace)), sink.outText());
        assertTrue(sink.outText().contains("filled in the defaults"), sink.outText());
    }

    @Test
    void withoutATagOnlyTheConfigIsWritten(@TempDir Path workspace) throws IOException {
        Init.run(workspace, null, log);

        assertTrue(Files.isRegularFile(Layout.configFile(workspace)));
        try (var entries = Files.list(workspace)) {
            assertEquals(1, entries.count(), "nothing but the state directory is laid out");
        }
    }

    @Test
    void rejectsATagThatWouldEscapeTheWorkspace(@TempDir Path workspace) {
        UserError error = assertThrows(UserError.class, () -> Init.run(workspace, "../client", log));

        assertTrue(error.getMessage().contains("invalid tag"), error.getMessage());
        assertFalse(Files.exists(workspace.resolve("client")), "no tag directory escapes the workspace");
    }
}

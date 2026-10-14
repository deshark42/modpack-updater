package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.log.Log;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitTest {

    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(printed, true, StandardCharsets.UTF_8);
    private final Log log = new Log(out);

    @Test
    void writesAConfigTemplateAndThePublishInputsOfATag(@TempDir Path workspace) throws IOException {
        Init.run(workspace, "client", log);

        Path config = Layout.configFile(workspace);
        assertTrue(Files.isRegularFile(config), printed.toString());
        assertTrue(Files.isDirectory(Layout.baseDir(workspace, "client")), printed.toString());
        assertTrue(Files.isDirectory(Layout.overlayDir(workspace, "client")), printed.toString());
        assertTrue(Files.isRegularFile(Layout.removeFile(workspace, "client")), printed.toString());
        assertTrue(printed.toString().contains("created  .modpack-updater/config.json"), printed.toString());
        assertTrue(printed.toString().contains("created  client/base"), printed.toString());
        assertTrue(printed.toString().contains("created  client/overlay"), printed.toString());
        assertTrue(printed.toString().contains("created  client/remove.txt"), printed.toString());
        assertTrue(printed.toString().contains("fill in \"endpoint\" and \"bucket\""), printed.toString());
        assertTrue(rulesIn(Layout.removeFile(workspace, "client")).isEmpty(),
                Files.readString(Layout.removeFile(workspace, "client")));

        // Pointing the template at a bucket is all a config needs to become loadable.
        String filled = Files.readString(config)
                .replace("\"endpoint\": \"\"", "\"endpoint\": \"https://s3.example.com\"")
                .replace("\"bucket\": \"\"", "\"bucket\": \"pack\"");
        Config parsed = Config.parse(filled, "config.json");
        assertEquals("pack", parsed.bucket());
        assertTrue(parsed.anonymous(), "empty keys mean a public bucket");
    }

    @Test
    void keepsWhatIsAlreadyThere(@TempDir Path workspace) throws IOException {
        Path config = Layout.configFile(workspace);
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"endpoint\": \"https://mine\", \"bucket\": \"b\", \"region\": \"eu-west-1\","
                + " \"prefix\": \"\", \"path_style\": true, \"concurrency\": 8}");
        Files.createDirectories(Layout.baseDir(workspace, "client"));
        Files.createDirectories(Layout.overlayDir(workspace, "client"));
        Files.writeString(Layout.removeFile(workspace, "client"), "example-mod-*.jar\n");

        Init.run(workspace, "client", log);

        Config kept = Config.load(workspace);
        assertEquals("https://mine", kept.endpoint());
        assertEquals("eu-west-1", kept.region());
        assertTrue(kept.pathStyle());
        assertEquals(8, kept.concurrency());
        assertTrue(printed.toString().contains("kept     .modpack-updater/config.json"), printed.toString());
        assertTrue(printed.toString().contains("kept     client/base"), printed.toString());
        assertTrue(printed.toString().contains("kept     client/overlay"), printed.toString());
        assertTrue(printed.toString().contains("kept     client/remove.txt"), printed.toString());
        assertFalse(printed.toString().contains("created "), printed.toString());
        assertEquals("example-mod-*.jar\n", Files.readString(Layout.removeFile(workspace, "client")));
    }

    @Test
    void fillsInTheDefaultsAConfigIsMissing(@TempDir Path workspace) throws IOException {
        Path config = Layout.configFile(workspace);
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"endpoint\": \"https://mine\", \"bucket\": \"b\"}");

        Init.run(workspace, null, log);

        assertEquals(4, Config.load(workspace).concurrency());
        assertTrue(Files.readString(config).contains("\"concurrency\": 4"), Files.readString(config));
        assertTrue(printed.toString().contains("filled in the defaults it was missing"), printed.toString());
    }

    @Test
    void withoutATagOnlyTheConfigIsWritten(@TempDir Path workspace) throws IOException {
        Init.run(workspace, null, log);

        assertTrue(Files.isRegularFile(Layout.configFile(workspace)));
        try (var entries = Files.list(workspace)) {
            assertEquals(1, entries.count());
        }
    }

    @Test
    void rejectsATagThatWouldEscapeTheWorkspace(@TempDir Path workspace) {
        assertThrows(UserError.class, () -> Init.run(workspace, "../client", log));
    }

    private static List<String> rulesIn(Path file) throws IOException {
        return Files.readAllLines(file).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }
}

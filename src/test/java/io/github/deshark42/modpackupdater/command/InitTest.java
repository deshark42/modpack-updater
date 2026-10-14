package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitTest {

    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(printed, true, StandardCharsets.UTF_8);

    @Test
    void writesAConfigTemplateAndTheBaseDirectoryOfATag(@TempDir Path workspace) throws IOException {
        Init.run(workspace, "client", out);

        Path config = Layout.configFile(workspace);
        assertTrue(Files.isRegularFile(config), printed.toString());
        assertTrue(Files.isDirectory(Layout.baseDir(workspace, "client")), printed.toString());
        assertTrue(printed.toString().contains("created " + config), printed.toString());

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
        Files.writeString(config, "{\"endpoint\": \"https://mine\", \"bucket\": \"b\"}");
        Files.createDirectories(Layout.baseDir(workspace, "client"));

        Init.run(workspace, "client", out);

        assertEquals("{\"endpoint\": \"https://mine\", \"bucket\": \"b\"}", Files.readString(config));
        assertTrue(printed.toString().contains("kept    " + config), printed.toString());
        assertFalse(printed.toString().contains("created "), printed.toString());
    }

    @Test
    void withoutATagOnlyTheConfigIsWritten(@TempDir Path workspace) throws IOException {
        Init.run(workspace, null, out);

        assertTrue(Files.isRegularFile(Layout.configFile(workspace)));
        try (var entries = Files.list(workspace)) {
            assertEquals(1, entries.count());
        }
    }

    @Test
    void rejectsATagThatWouldEscapeTheWorkspace(@TempDir Path workspace) {
        assertThrows(UserError.class, () -> Init.run(workspace, "../client", out));
    }
}

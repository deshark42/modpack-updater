package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

    @Test
    void appliesDefaultsForOptionalFields() {
        Config config = Config.parse("{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\"}", "config.json");
        assertEquals("us-east-1", config.region());
        assertEquals("", config.prefix());
        assertFalse(config.pathStyle());
        assertTrue(config.anonymous());
    }

    @Test
    void defaultsToFourTransfersAtATime() {
        Config config = Config.parse("{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\"}", "config.json");
        assertEquals(4, config.concurrency());
    }

    @Test
    void readsHowManyTransfersMayRunAtOnce() {
        Config config = Config.parse("{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\","
                + " \"concurrency\": 8}", "config.json");
        assertEquals(8, config.concurrency());
    }

    @Test
    void rejectsAConcurrencyThatIsNotAUsableCount() {
        String head = "{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\", \"concurrency\": ";
        assertThrows(UserError.class, () -> Config.parse(head + "0}", "config.json"));
        assertThrows(UserError.class, () -> Config.parse(head + "1000}", "config.json"));
        assertThrows(UserError.class, () -> Config.parse(head + "\"many\"}", "config.json"));
    }

    @Test
    void normalizesEndpointAndPrefix() {
        Config config = Config.parse(
                "{\"endpoint\": \"https://s3.example.com/\", \"bucket\": \"pack\", \"prefix\": \"gtnh\"}", "config.json");
        assertEquals("https://s3.example.com", config.endpoint());
        assertEquals("gtnh/", config.prefix());
    }

    @Test
    void readsCredentials() {
        Config config = Config.parse("{\"endpoint\": \"http://127.0.0.1:9000\", \"bucket\": \"pack\","
                + " \"path_style\": true, \"access_key\": \"a\", \"secret_key\": \"s\"}", "config.json");
        assertTrue(config.pathStyle());
        assertFalse(config.anonymous());
        assertEquals("s", config.secretKey());
    }

    @Test
    void ignoresBlankCredentials() {
        Config config = Config.parse(
                "{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\", \"access_key\": \"\", \"secret_key\": \"\"}",
                "config.json");
        assertTrue(config.anonymous());
    }

    @Test
    void rejectsHalfCredentials() {
        UserError error = assertThrows(UserError.class, () -> Config.parse(
                "{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\", \"access_key\": \"a\"}", "config.json"));
        assertTrue(error.getMessage().contains("both"));
    }

    @Test
    void rejectsMissingEndpoint() {
        assertThrows(UserError.class, () -> Config.parse("{\"bucket\": \"pack\"}", "config.json"));
    }

    @Test
    void rejectsNonHttpEndpoint() {
        assertThrows(UserError.class,
                () -> Config.parse("{\"endpoint\": \"s3.example.com\", \"bucket\": \"pack\"}", "config.json"));
    }

    @Test
    void loadsFromWorkspace(@TempDir Path root) {
        AtomicFile.write(Layout.configFile(root), "{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\"}");
        assertEquals("pack", Config.load(root).bucket());
    }

    @Test
    void writesBackTheDefaultsAnOlderConfigIsMissing(@TempDir Path root) throws IOException {
        Path file = Layout.configFile(root);
        AtomicFile.write(file, """
                {
                  "endpoint": "https://s3.example.com",
                  "bucket": "pack",
                  "access_key": "",
                  "secret_key": ""
                }
                """);

        assertTrue(Config.fillMissing(root), "region, prefix, path_style and concurrency were missing");
        assertFalse(Config.fillMissing(root), "the second look finds nothing to add");

        Config config = Config.load(root);
        assertEquals("us-east-1", config.region());
        assertEquals("", config.prefix());
        assertFalse(config.pathStyle());
        assertEquals(4, config.concurrency());
        String written = Files.readString(file);
        assertTrue(written.contains("\"endpoint\": \"https://s3.example.com\""), written);
        assertTrue(written.contains("\"concurrency\": 4"), written);
        assertTrue(written.contains("\n  \"region\": \"us-east-1\",\n"), written);
    }

    @Test
    void keepsKeysItDoesNotKnow(@TempDir Path root) throws IOException {
        Path file = Layout.configFile(root);
        AtomicFile.write(file, "{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\", \"note\": \"mine\"}");

        assertTrue(Config.fillMissing(root));

        assertTrue(Files.readString(file).contains("\"note\": \"mine\""), Files.readString(file));
    }

    @Test
    void reportsMissingConfig(@TempDir Path root) {
        assertThrows(UserError.class, () -> Config.load(root));
    }
}

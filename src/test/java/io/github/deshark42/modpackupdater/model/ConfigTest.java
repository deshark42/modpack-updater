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

    private static final String MINIMAL = "{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\"}";

    @Test
    void appliesDefaultsForOptionalFields() {
        Config config = Config.parse(MINIMAL, "config.json");
        assertEquals("us-east-1", config.region());
        assertEquals("", config.prefix());
        assertFalse(config.pathStyle());
        assertTrue(config.anonymous());
    }

    @Test
    void defaultsToTheDocumentedTransferCount() {
        assertEquals(Config.DEFAULT_CONCURRENCY, Config.parse(MINIMAL, "config.json").concurrency());
    }

    @Test
    void readsHowManyTransfersMayRunAtOnce() {
        Config config = Config.parse("{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\","
                + " \"concurrency\": 3}", "config.json");
        assertEquals(3, config.concurrency());
    }

    @Test
    void rejectsAConcurrencyOutsideTheAllowedRange() {
        String head = "{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\", \"concurrency\": ";
        for (String bad : new String[]{"0", String.valueOf(Config.MAX_CONCURRENCY + 1)}) {
            UserError error = assertThrows(UserError.class, () -> Config.parse(head + bad + "}", "config.json"), bad);
            assertTrue(error.getMessage().contains("\"concurrency\" must be between 1 and " + Config.MAX_CONCURRENCY),
                    error.getMessage());
        }
    }

    @Test
    void rejectsAConcurrencyThatIsNotANumber() {
        UserError error = assertThrows(UserError.class, () -> Config.parse("{\"endpoint\": \"https://s3.example.com\","
                + " \"bucket\": \"pack\", \"concurrency\": \"many\"}", "config.json"));
        assertTrue(error.getMessage().contains("\"concurrency\" must be an integer"), error.getMessage());
    }

    @Test
    void normalizesEndpointAndPrefix() {
        Config config = Config.parse(
                "{\"endpoint\": \"https://s3.example.com///\", \"bucket\": \"pack\", \"prefix\": \"gtnh\"}",
                "config.json");
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
        assertTrue(error.getMessage().contains("set both"), error.getMessage());
    }

    @Test
    void rejectsMissingEndpoint() {
        UserError error = assertThrows(UserError.class, () -> Config.parse("{\"bucket\": \"pack\"}", "config.json"));
        assertTrue(error.getMessage().contains("missing a string \"endpoint\""), error.getMessage());
    }

    @Test
    void rejectsNonHttpEndpoint() {
        UserError error = assertThrows(UserError.class,
                () -> Config.parse("{\"endpoint\": \"s3.example.com\", \"bucket\": \"pack\"}", "config.json"));
        assertTrue(error.getMessage().contains("\"endpoint\" must be an http(s) URL"), error.getMessage());
    }

    @Test
    void rejectsAnEmptyBucket() {
        UserError error = assertThrows(UserError.class,
                () -> Config.parse("{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"\"}", "config.json"));
        assertTrue(error.getMessage().contains("\"bucket\" must not be empty"), error.getMessage());
    }

    @Test
    void loadsFromWorkspace(@TempDir Path root) {
        AtomicFile.write(Layout.configFile(root), MINIMAL);
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
        assertEquals(Config.DEFAULT_CONCURRENCY, config.concurrency());
        String written = Files.readString(file);
        assertTrue(written.contains("\"endpoint\": \"https://s3.example.com\""), written);
        assertTrue(written.contains("\"concurrency\": " + Config.DEFAULT_CONCURRENCY), written);
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
        UserError error = assertThrows(UserError.class, () -> Config.load(root));
        assertTrue(error.getMessage().contains("needs a " + Layout.STATE_DIR + "/" + Layout.CONFIG),
                error.getMessage());
    }
}

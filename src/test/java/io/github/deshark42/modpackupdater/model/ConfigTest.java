package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigTest {

    private static final String MINIMAL = "{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\"}";

    @Test
    void appliesDefaultsForOptionalFields() {
        Config config = Config.parse(MINIMAL, "config.json");
        assertEquals("us-east-1", config.region());
        assertEquals("", config.prefix());
        assertFalse(config.pathStyle());
        assertTrue(config.anonymous());
        assertNull(config.readEndpoint(), "without a CDN the objects are read from the store itself");
        assertEquals(Layout.DEFAULT_PRIVATE_KEY, config.signingPrivateKey());
        assertEquals(Layout.DEFAULT_PUBLIC_KEY, config.trustedPublicKey());
    }

    @ParameterizedTest(name = "key path {0} cannot be replaced through a release")
    @ValueSource(strings = {"mods/key.pem", "client/base/key.pem", "public.pem"})
    void rejectsSigningKeysInThePublishableWorkspace(String path, @TempDir Path root) {
        Config config = new Config("https://s3.example.com", null, "us-east-1", "pack", "", false,
                null, null, Config.DEFAULT_CONCURRENCY, path, path);
        UserError error = assertThrows(UserError.class, () -> config.publicKeyFile(root));
        assertTrue(error.getMessage().contains("must stay under " + Layout.STATE_DIR), error.getMessage());
    }

    @Test
    void readsConfiguredLocalSigningKeyPaths() {
        Config config = Config.parse("{\"endpoint\":\"https://s3.example.com\",\"bucket\":\"pack\","
                + "\"signing_private_key\":\".modpack-updater/custom-private.pem\","
                + "\"trusted_public_key\":\".modpack-updater/custom-public.pem\"}", "config.json");
        assertEquals(".modpack-updater/custom-private.pem", config.signingPrivateKey());
        assertEquals(".modpack-updater/custom-public.pem", config.trustedPublicKey());
    }

    @Test
    void rejectsAnExternalKeyAliasBackIntoPublishableContent(@TempDir Path root) throws IOException {
        Path workspace = Files.createDirectory(root.resolve("instance"));
        Path payload = workspace.resolve("mods/public.pem");
        Files.createDirectories(payload.getParent());
        Files.writeString(payload, "replaceable public key");
        Path alias = root.resolve("outside-key.pem");
        try {
            Files.createSymbolicLink(alias, payload);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links need filesystem support and privileges: " + e);
        }
        Config config = new Config("https://s3.example.com", null, "us-east-1", "pack", "", false,
                null, null, Config.DEFAULT_CONCURRENCY, Layout.DEFAULT_PRIVATE_KEY, alias.toString());

        UserError error = assertThrows(UserError.class, () -> config.publicKeyFile(workspace));

        assertTrue(error.getMessage().contains("must stay under " + Layout.STATE_DIR), error.getMessage());
        assertEquals("replaceable public key", Files.readString(payload));
    }

    @Test
    void rejectsAMissingExternalKeyWhoseParentAliasesPublishableContent(@TempDir Path root) throws IOException {
        Path workspace = Files.createDirectory(root.resolve("instance"));
        Path alias = root.resolve("outside-keys");
        try {
            Files.createSymbolicLink(alias, workspace);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links need filesystem support and privileges: " + e);
        }
        String futureKey = alias.resolve("mods/future-private.pem").toString();
        Config config = new Config("https://s3.example.com", null, "us-east-1", "pack", "", false,
                null, null, Config.DEFAULT_CONCURRENCY, futureKey, Layout.DEFAULT_PUBLIC_KEY);

        UserError error = assertThrows(UserError.class, () -> config.privateKeyFile(workspace));

        assertTrue(error.getMessage().contains("must stay under " + Layout.STATE_DIR), error.getMessage());
        assertFalse(Files.exists(workspace.resolve("mods/future-private.pem")));
    }

    @Test
    void rejectsAMissingKeyBelowAnUnresolvedExternalLink(@TempDir Path root) throws IOException {
        Path workspace = Files.createDirectory(root.resolve("instance"));
        Path alias = root.resolve("outside-keys");
        try {
            Files.createSymbolicLink(alias, workspace.resolve("future-mods"));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links need filesystem support and privileges: " + e);
        }
        String futureKey = alias.resolve("private.pem").toString();
        Config config = new Config("https://s3.example.com", null, "us-east-1", "pack", "", false,
                null, null, Config.DEFAULT_CONCURRENCY, futureKey, Layout.DEFAULT_PUBLIC_KEY);

        UserError error = assertThrows(UserError.class, () -> config.privateKeyFile(workspace));

        assertTrue(error.getMessage().contains("cannot resolve local signing key path"), error.getMessage());
        assertFalse(Files.exists(workspace.resolve("future-mods/private.pem")));
    }

    @Test
    void resolvesConfiguredKeysRelativeToTheWorkspaceAndPermitsExternalKeys(@TempDir Path root) {
        Path workspace = root.resolve("instance");
        Config config = new Config("https://s3.example.com", null, "us-east-1", "pack", "", false,
                null, null, Config.DEFAULT_CONCURRENCY, root.resolve("private.pem").toString(),
                Layout.STATE_DIR + "/custom-public.pem");
        assertEquals(root.resolve("private.pem"), config.privateKeyFile(workspace));
        assertEquals(workspace.resolve(Layout.STATE_DIR).resolve("custom-public.pem"), config.publicKeyFile(workspace));
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
    void readsTheAddressOfTheCdnInFrontOfTheBucket() {
        Config config = Config.parse("{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\","
                + " \"read_endpoint\": \"https://cdn.example.com/modpacks///\"}", "config.json");
        assertEquals("https://cdn.example.com/modpacks", config.readEndpoint(),
                "a path survives, the trailing slashes that would double the key's separator do not");
    }

    @Test
    void takesABlankReadEndpointAsNoCdn() {
        Config config = Config.parse("{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\","
                + " \"read_endpoint\": \"\"}", "config.json");
        assertNull(config.readEndpoint(), "the empty entry of a fresh template is not an address");
    }

    @Test
    void allowsAPathAndAnIpAddressInTheReadEndpoint() {
        Config config = Config.parse("{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\","
                + " \"read_endpoint\": \"http://127.0.0.1:9000/edge\"}", "config.json");
        assertEquals("http://127.0.0.1:9000/edge", config.readEndpoint(),
                "the read address is used as given, so neither a path nor an IP address needs path_style");
    }

    @Test
    void rejectsAReadEndpointThatIsNotAnHttpUrl() {
        UserError error = assertThrows(UserError.class, () -> Config.parse(
                "{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\","
                        + " \"read_endpoint\": \"cdn.example.com\"}", "config.json"));
        assertTrue(error.getMessage().contains("\"read_endpoint\" must be an http(s) URL"), error.getMessage());
    }

    @Test
    void rejectsAReadEndpointWithoutAHost() {
        UserError error = assertThrows(UserError.class, () -> Config.parse(
                "{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\","
                        + " \"read_endpoint\": \"http://\"}", "config.json"));
        assertTrue(error.getMessage().contains("\"read_endpoint\" must be an http(s) URL with a host"),
                error.getMessage());
    }

    @Test
    void rejectsAReadEndpointThatCarriesMoreThanAHostAndPath() {
        for (String read : new String[]{"https://cdn.example.com?x=1", "https://cdn.example.com#x",
                "https://user:secret@cdn.example.com"}) {
            UserError error = assertThrows(UserError.class, () -> Config.parse(
                    "{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\","
                            + " \"read_endpoint\": \"" + read + "\"}", "config.json"), read);
            assertTrue(error.getMessage().contains("no query or credentials"), read + ": " + error.getMessage());
        }
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
    void rejectsAnEndpointWithoutAHost() {
        UserError error = assertThrows(UserError.class,
                () -> Config.parse("{\"endpoint\": \"http://\", \"bucket\": \"pack\"}", "config.json"));
        assertTrue(error.getMessage().contains("\"endpoint\" must be an http(s) URL with a host"), error.getMessage());
    }

    @Test
    void rejectsAnEndpointThatCarriesMoreThanAHost() {
        for (String endpoint : new String[]{"https://s3.example.com/base", "https://s3.example.com?x=1"}) {
            UserError error = assertThrows(UserError.class, () -> Config.parse(
                    "{\"endpoint\": \"" + endpoint + "\", \"bucket\": \"pack\"}", "config.json"), endpoint);
            assertTrue(error.getMessage().contains("no path, query or credentials"), endpoint + ": " + error.getMessage());
        }
    }

    @Test
    void rejectsAVirtualHostBucketOnAnIpAddress() {
        UserError error = assertThrows(UserError.class, () -> Config.parse(
                "{\"endpoint\": \"http://127.0.0.1:9000\", \"bucket\": \"pack\"}", "config.json"));
        assertTrue(error.getMessage().contains("\"path_style\" must be true"), error.getMessage());

        Config pathStyle = Config.parse("{\"endpoint\": \"http://127.0.0.1:9000\", \"bucket\": \"pack\","
                + " \"path_style\": true}", "config.json");
        assertTrue(pathStyle.pathStyle(), "path style addresses the bucket in the path");
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

        assertTrue(Config.fillMissing(root), "region, prefix, path_style, read_endpoint and concurrency were missing");
        assertFalse(Config.fillMissing(root), "the second look finds nothing to add");

        Config config = Config.load(root);
        assertEquals("us-east-1", config.region());
        assertEquals("", config.prefix());
        assertFalse(config.pathStyle());
        assertEquals(Config.DEFAULT_CONCURRENCY, config.concurrency());
        String written = Files.readString(file);
        assertTrue(written.contains("\"endpoint\": \"https://s3.example.com\""), written);
        assertTrue(written.contains("\"read_endpoint\": \"\""), written);
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

package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import io.github.deshark42.modpackupdater.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigTest {

    private static final String MINIMAL = "{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\"}";

    private static Map<String, Object> completeDocument() {
        return Map.ofEntries(
                Map.entry("endpoint", "https://s3.example.com"), Map.entry("bucket", "pack"),
                Map.entry("access_key", ""), Map.entry("secret_key", ""),
                Map.entry("read_endpoint", ""), Map.entry("region", "us-east-1"),
                Map.entry("prefix", ""), Map.entry("path_style", false),
                Map.entry("concurrency", (long) Config.DEFAULT_CONCURRENCY),
                Map.entry("signing_private_key", Layout.DEFAULT_PRIVATE_KEY),
                Map.entry("trusted_public_key", Layout.DEFAULT_PUBLIC_KEY));
    }

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
        assertEquals(Config.DEFAULT_CONCURRENCY, config.concurrency());
    }

    @ParameterizedTest(name = "key path {0} cannot be replaced through a release")
    @ValueSource(strings = {"mods/key.pem", "client/base/key.pem", "public.pem"})
    void rejectsSigningKeysInThePublishableWorkspace(String path, @TempDir Path root) {
        Config config = new Config("https://s3.example.com", null, "us-east-1", "pack", "", false,
                null, null, Config.DEFAULT_CONCURRENCY, path, path);
        UserError error = assertThrows(UserError.class, () -> config.publicKeyFile(root));
        assertTrue(error.getMessage().contains("must stay under " + Layout.STATE_DIR), error.getMessage());
        assertFalse(Files.exists(root.resolve(path)));
        assertFalse(Files.exists(Layout.stateDir(root)));
    }

    @Test
    void aPrivateKeyCannotBeWrittenIntoPublishableContent(@TempDir Path root) {
        Config config = new Config("https://s3.example.com", null, "us-east-1", "pack", "", false,
                null, null, Config.DEFAULT_CONCURRENCY, "mods/private.pem", Layout.DEFAULT_PUBLIC_KEY);

        UserError error = assertThrows(UserError.class, () -> config.privateKeyFile(root));

        assertTrue(error.getMessage().contains("must stay under " + Layout.STATE_DIR), error.getMessage());
        assertFalse(Files.exists(root.resolve("mods/private.pem")));
        assertFalse(Files.exists(Layout.stateDir(root)));
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
    void anExternalPrivateKeyRemainsOutsideTheWorkspace(@TempDir Path root) {
        Path workspace = root.resolve("instance");
        Config config = new Config("https://s3.example.com", null, "us-east-1", "pack", "", false,
                null, null, Config.DEFAULT_CONCURRENCY, root.resolve("private.pem").toString(),
                Layout.DEFAULT_PUBLIC_KEY);

        assertEquals(root.resolve("private.pem"), config.privateKeyFile(workspace));
    }

    @Test
    void aRelativePublicKeyResolvesUnderTheWorkspace(@TempDir Path root) {
        Path workspace = root.resolve("instance");
        Config config = new Config("https://s3.example.com", null, "us-east-1", "pack", "", false,
                null, null, Config.DEFAULT_CONCURRENCY, Layout.DEFAULT_PRIVATE_KEY,
                Layout.STATE_DIR + "/custom-public.pem");

        assertEquals(workspace.resolve(Layout.STATE_DIR).resolve("custom-public.pem"), config.publicKeyFile(workspace));
    }

    @Test
    void readsHowManyTransfersMayRunAtOnce() {
        Config config = Config.parse("{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\","
                + " \"concurrency\": 3}", "config.json");
        assertEquals(3, config.concurrency());
    }

    @Test
    void zeroConcurrentTransfersAreRejected() {
        UserError error = assertThrows(UserError.class, () -> Config.parse(
                "{\"endpoint\":\"https://s3.example.com\",\"bucket\":\"pack\",\"concurrency\":0}", "config.json"));

        assertTrue(error.getMessage().contains("\"concurrency\" must be between 1 and " + Config.MAX_CONCURRENCY),
                error.getMessage());
    }

    @Test
    void aTransferCountAboveTheMaximumIsRejected() {
        UserError error = assertThrows(UserError.class, () -> Config.parse(
                "{\"endpoint\":\"https://s3.example.com\",\"bucket\":\"pack\",\"concurrency\":"
                        + (Config.MAX_CONCURRENCY + 1) + "}", "config.json"));

        assertTrue(error.getMessage().contains("\"concurrency\" must be between 1 and " + Config.MAX_CONCURRENCY),
                error.getMessage());
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

    @ParameterizedTest(name = "read endpoint {0} carries unsupported URL components")
    @ValueSource(strings = {"https://cdn.example.com?x=1", "https://cdn.example.com#x",
            "https://user:secret@cdn.example.com"})
    void aReadEndpointCannotCarryQueriesFragmentsOrCredentials(String read) {
        UserError error = assertThrows(UserError.class, () -> Config.parse(
                "{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\","
                        + " \"read_endpoint\": \"" + read + "\"}", "config.json"));

        assertTrue(error.getMessage().contains("no query or credentials"), error.getMessage());
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
    void anEndpointCannotContainAPath() {
        UserError error = assertThrows(UserError.class, () -> Config.parse(
                "{\"endpoint\":\"https://s3.example.com/base\",\"bucket\":\"pack\"}", "config.json"));

        assertTrue(error.getMessage().contains("no path, query or credentials"), error.getMessage());
    }

    @Test
    void anEndpointCannotContainAQuery() {
        UserError error = assertThrows(UserError.class, () -> Config.parse(
                "{\"endpoint\":\"https://s3.example.com?x=1\",\"bucket\":\"pack\"}", "config.json"));

        assertTrue(error.getMessage().contains("no path, query or credentials"), error.getMessage());
    }

    @Test
    void rejectsAVirtualHostBucketOnAnIpAddress() {
        UserError error = assertThrows(UserError.class, () -> Config.parse(
                "{\"endpoint\": \"http://127.0.0.1:9000\", \"bucket\": \"pack\"}", "config.json"));
        assertTrue(error.getMessage().contains("\"path_style\" must be true"), error.getMessage());
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

        boolean changed = Config.fillMissing(root);

        assertTrue(changed, "region, prefix, path_style, read_endpoint and concurrency were missing");

        Config config = Config.load(root);
        assertEquals("us-east-1", config.region());
        assertEquals("", config.prefix());
        assertFalse(config.pathStyle());
        assertEquals(Config.DEFAULT_CONCURRENCY, config.concurrency());
        assertEquals(completeDocument(), Json.parse(Files.readString(file)));
    }

    @Test
    void aCompleteConfigIsNotRewritten(@TempDir Path root) throws IOException {
        Path file = Layout.configFile(root);
        AtomicFile.write(file, Json.write(completeDocument()));
        String before = Files.readString(file);

        boolean changed = Config.fillMissing(root);

        assertFalse(changed);
        assertEquals(before, Files.readString(file));
    }

    @Test
    void keepsKeysItDoesNotKnow(@TempDir Path root) throws IOException {
        Path file = Layout.configFile(root);
        AtomicFile.write(file, "{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\", \"note\": \"mine\"}");

        assertTrue(Config.fillMissing(root));

        assertEquals("mine", Json.asObject(Json.parse(Files.readString(file)), file.toString()).get("note"));
    }

    @Test
    void reportsMissingConfig(@TempDir Path root) {
        UserError error = assertThrows(UserError.class, () -> Config.load(root));
        assertTrue(error.getMessage().contains("needs a " + Layout.STATE_DIR + "/" + Layout.CONFIG),
                error.getMessage());
        assertFalse(Files.exists(Layout.stateDir(root)));
    }
}

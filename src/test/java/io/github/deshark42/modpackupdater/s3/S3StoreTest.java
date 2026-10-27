package io.github.deshark42.modpackupdater.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class S3StoreTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);

    private static S3Store store(Config config) {
        return new S3Store(config, CLOCK, Duration.ofSeconds(5));
    }

    private static Config config(FakeS3 s3, String secretKey) {
        return new Config(s3.url(), null, TestWorkspace.REGION, TestWorkspace.BUCKET, TestWorkspace.PREFIX,
                true, TestWorkspace.ACCESS_KEY, secretKey, Config.DEFAULT_CONCURRENCY);
    }

    private static byte[] gzip(byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(content);
        }
        return bytes.toByteArray();
    }

    @Test
    void anUploadStoresTheSignedBytes() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] content = TestFiles.bytes("content");
            String relative = ObjectKeys.blob(Sha256.ofBytes(content));

            store(TestWorkspace.config(s3)).putBytes(relative, content);

            assertEquals(List.of("PUT " + TestWorkspace.bucketPath(relative)), s3.requests());
            FakeS3.Request sent = s3.requestsDetailed().get(0);
            assertEquals(Sha256.ofBytes(content), sent.headers().get("x-amz-content-sha256"));
            assertEquals(ObjectKeys.cacheControl(relative), sent.headers().get("cache-control"));
            assertEquals("20261020T000000Z", sent.headers().get("x-amz-date"));
            assertArrayEquals(content, s3.object(TestWorkspace.bucketPath(relative)));
        }
    }

    @Test
    void aReadReturnsTheStoredBytes() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] content = TestFiles.bytes("content");
            String relative = ObjectKeys.blob(Sha256.ofBytes(content));
            s3.put(TestWorkspace.bucketPath(relative), content);

            byte[] actual = store(TestWorkspace.config(s3)).getBytes(relative);

            assertArrayEquals(content, actual);
            assertEquals(List.of("GET " + TestWorkspace.bucketPath(relative)), s3.requests());
        }
    }

    @Test
    void aCompressedUploadStoresAGzipMemberAndReportsItsSize() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] content = TestFiles.bytes("manifest ".repeat(20));
            String relative = ObjectKeys.release("client", "1.0.0");

            int stored = store(TestWorkspace.config(s3)).putGzip(relative, content);

            String path = TestWorkspace.bucketPath(relative);
            byte[] storedBytes = s3.object(path);
            assertEquals(gzip(content).length, stored);
            assertArrayEquals(gzip(content), storedBytes);
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(storedBytes))) {
                assertArrayEquals(content, gzip.readAllBytes());
            }
            assertEquals(List.of("PUT " + path), s3.requests());
            assertEquals(S3Store.GZIP, s3.contentEncoding(path));
            assertEquals(Sha256.ofBytes(storedBytes),
                    s3.requestsDetailed().get(0).headers().get("x-amz-content-sha256"));
        }
    }

    @Test
    void aReadInflatesTheEncodingAdvertisedByTheServer() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] content = TestFiles.bytes("manifest");
            String relative = ObjectKeys.release("client", "1.0.0");
            s3.put(TestWorkspace.bucketPath(relative), gzip(content), S3Store.GZIP);

            byte[] actual = store(TestWorkspace.config(s3)).getBytes(relative);

            assertArrayEquals(content, actual);
            assertEquals(List.of("GET " + TestWorkspace.bucketPath(relative)), s3.requests());
        }
    }

    @Test
    void anAnonymousStoreSendsNoCredentials() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            s3.allowAnonymous(true);
            Config anonymous = new Config(s3.url(), null, TestWorkspace.REGION, TestWorkspace.BUCKET,
                    TestWorkspace.PREFIX, true, null, null, Config.DEFAULT_CONCURRENCY);
            String relative = ObjectKeys.latest("client");
            byte[] content = TestFiles.bytes("pointer");
            s3.put(TestWorkspace.bucketPath(relative), content);

            byte[] actual = store(anonymous).getBytes(relative);

            assertArrayEquals(content, actual);
            assertEquals(List.of("GET " + TestWorkspace.bucketPath(relative)), s3.requests());
            assertFalse(s3.requestsDetailed().get(0).headers().containsKey("authorization"));
            assertFalse(s3.requestsDetailed().get(0).headers().containsKey("x-amz-date"));
        }
    }

    @Test
    void aDownloadWritesOnlyTheTargetPath(@TempDir Path dir) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] content = TestFiles.bytes("bytes");
            String relative = ObjectKeys.blob(Sha256.ofBytes(content));
            s3.put(TestWorkspace.bucketPath(relative), content);
            Path target = dir.resolve("mods/deep/a.jar");

            store(TestWorkspace.config(s3)).download(relative, target);

            assertArrayEquals(content, Files.readAllBytes(target));
            assertEquals(List.of("GET " + TestWorkspace.bucketPath(relative)), s3.requests());
            try (var entries = Files.list(target.getParent())) {
                assertEquals(List.of(target), entries.toList(), "no temporary file is left behind");
            }
        }
    }

    @ParameterizedTest(name = "HTTP {0} reports {1} without a second request")
    @CsvSource({"404,not found,NoSuchKey", "403,access denied,AccessDenied", "500,HTTP 500,InternalError"})
    void aFailedReadNamesTheObjectAndReason(int status, String reason, String code) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            String relative = ObjectKeys.latest("client");
            String path = TestWorkspace.bucketPath(relative);
            s3.fail("GET", path, status);

            UserError error = assertThrows(UserError.class,
                    () -> store(TestWorkspace.config(s3)).getBytes(relative));

            assertTrue(error.getMessage().contains(reason), error.getMessage());
            assertTrue(error.getMessage().contains(path.substring(1)), error.getMessage());
            assertTrue(error.getMessage().contains(code), error.getMessage());
            assertEquals(List.of("GET " + path), s3.requests());
            assertNull(s3.object(path));
        }
    }

    @Test
    void aRequestSignedWithAnotherSecretLeavesTheObjectUnchanged() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            String relative = ObjectKeys.latest("client");
            byte[] original = TestFiles.bytes("pointer");
            String path = TestWorkspace.bucketPath(relative);
            s3.put(path, original);

            UserError error = assertThrows(UserError.class,
                    () -> store(config(s3, "not-the-secret")).putBytes(relative, TestFiles.bytes("replacement")));

            assertTrue(error.getMessage().contains("access denied"), error.getMessage());
            assertTrue(error.getMessage().contains("SignatureDoesNotMatch"), error.getMessage());
            assertEquals(List.of("PUT " + path), s3.requests());
            assertArrayEquals(original, s3.object(path));
        }
    }

    @Test
    void aFailedUploadReportsTheStatusAndStoresNothing() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] content = TestFiles.bytes("content");
            String relative = ObjectKeys.blob(Sha256.ofBytes(content));
            String path = TestWorkspace.bucketPath(relative);
            s3.fail("PUT", path, 500);

            UserError error = assertThrows(UserError.class,
                    () -> store(TestWorkspace.config(s3)).putBytes(relative, content));

            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
            assertNull(s3.object(path));
            assertEquals(List.of("PUT " + path), s3.requests());
        }
    }

    @Test
    void aFailedDownloadPreservesTheTargetAndLeavesNoPartialFile(@TempDir Path dir) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            String relative = ObjectKeys.latest("client");
            String path = TestWorkspace.bucketPath(relative);
            s3.fail("GET", path, 500);
            TestFiles.write(dir, "existing", "original");
            Path target = dir.resolve("existing");

            UserError error = assertThrows(UserError.class,
                    () -> store(TestWorkspace.config(s3)).download(relative, target));

            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
            assertEquals("original", Files.readString(target, StandardCharsets.UTF_8));
            assertEquals(List.of("GET " + path), s3.requests());
            try (var entries = Files.list(dir)) {
                assertEquals(List.of(target), entries.toList());
            }
        }
    }

    @Test
    void aMissingObjectDoesNotExist() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            String relative = ObjectKeys.latest("client");

            boolean exists = store(TestWorkspace.config(s3)).exists(relative);

            assertFalse(exists);
            assertEquals(List.of("HEAD " + TestWorkspace.bucketPath(relative)), s3.requests());
        }
    }

    @Test
    void aStoredObjectExistsWithoutDownloadingItsBytes() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            String relative = ObjectKeys.latest("client");
            s3.put(TestWorkspace.bucketPath(relative), TestFiles.bytes("pointer"));

            boolean exists = store(TestWorkspace.config(s3)).exists(relative);

            assertTrue(exists);
            assertEquals(List.of("HEAD " + TestWorkspace.bucketPath(relative)), s3.requests());
        }
    }

    @Test
    void anExistenceCheckDoesNotTreatAServerFailureAsAMiss() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            String relative = ObjectKeys.latest("client");
            String path = TestWorkspace.bucketPath(relative);
            s3.fail("HEAD", path, 500);

            UserError error = assertThrows(UserError.class,
                    () -> store(TestWorkspace.config(s3)).exists(relative));

            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
            assertEquals(List.of("HEAD " + path), s3.requests());
            assertNull(s3.object(path));
        }
    }

    @ParameterizedTest(name = "path style {0} with prefix {1} addresses {2}")
    @CsvSource({"true,'',https://s3.example.com/", "true,prefix/,https://s3.example.com/",
            "false,'',https://"})
    void anObjectUsesTheConfiguredBucketAddress(boolean pathStyle, String prefix, String address) {
        Config config = new Config("https://s3.example.com", null, TestWorkspace.REGION,
                TestWorkspace.BUCKET, prefix, pathStyle, null, null, Config.DEFAULT_CONCURRENCY);
        String key = ObjectKeys.latest("client");
        URI expected = URI.create(pathStyle ? address + TestWorkspace.BUCKET + "/" + prefix + key
                : address + TestWorkspace.BUCKET + ".s3.example.com/" + key);

        URI actual = store(config).uri(key);

        assertEquals(expected, actual);
    }

    @Test
    void anUnsupportedBucketAddressFailsBeforeSendingARequest() {
        Config config = new Config("https://s3.example.com", null, TestWorkspace.REGION, "pack set", "",
                false, null, null, Config.DEFAULT_CONCURRENCY);

        UserError error = assertThrows(UserError.class,
                () -> store(config).getBytes(ObjectKeys.latest("client")));

        assertTrue(error.getMessage().contains("cannot address"), error.getMessage());
        assertTrue(error.getMessage().contains("unsupported URI"), error.getMessage());
    }

    @Test
    void objectKeysEscapeSpacesButPreserveSlashes() {
        Config config = new Config("https://s3.example.com", null, TestWorkspace.REGION,
                TestWorkspace.BUCKET, "", true, null, null, Config.DEFAULT_CONCURRENCY);
        String relative = ObjectKeys.latest("client").replace("client", "a b");

        URI escaped = store(config).uri(relative);

        assertEquals(URI.create("https://s3.example.com/" + TestWorkspace.BUCKET + "/"
                + relative.replace(" ", "%20")), escaped);
        assertEquals("/" + TestWorkspace.BUCKET + "/" + relative, escaped.getPath());
    }
}

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
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class S3StoreTest {

    private static Config config(FakeS3 s3) {
        return config(s3, TestWorkspace.SECRET_KEY);
    }

    private static Config config(FakeS3 s3, String secretKey) {
        return new Config(s3.url(), TestWorkspace.REGION, TestWorkspace.BUCKET, TestWorkspace.PREFIX,
                true, TestWorkspace.ACCESS_KEY, secretKey, Config.DEFAULT_CONCURRENCY);
    }

    private static Config anonymous(FakeS3 s3) {
        return new Config(s3.url(), TestWorkspace.REGION, TestWorkspace.BUCKET, TestWorkspace.PREFIX,
                true, null, null, Config.DEFAULT_CONCURRENCY);
    }

    private static Config plain(String endpoint) {
        return new Config(endpoint, TestWorkspace.REGION, TestWorkspace.BUCKET, "", true, null, null,
                Config.DEFAULT_CONCURRENCY);
    }

    @Test
    void uploadsHashedBytesAndReadsThemBack() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            S3Store store = new S3Store(config(s3));
            byte[] content = TestFiles.bytes("content");
            String relative = ObjectKeys.blob(Sha256.ofBytes(content));

            store.putBytes(relative, content);

            List<FakeS3.Request> sent = s3.requestsDetailed();
            assertEquals(1, sent.size(), sent.toString());
            assertEquals("PUT", sent.get(0).method());
            assertEquals(TestWorkspace.bucketPath(relative), sent.get(0).path());
            assertEquals(Sha256.ofBytes(content), sent.get(0).headers().get("x-amz-content-sha256"));
            String authorization = sent.get(0).headers().get("authorization");
            assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=" + TestWorkspace.ACCESS_KEY + "/"),
                    authorization);
            assertTrue(authorization.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"),
                    authorization);
            assertArrayEquals(content, s3.object(TestWorkspace.bucketPath(relative)));

            assertArrayEquals(content, store.getBytes(relative));
            sent = s3.requestsDetailed();
            assertEquals("GET", sent.get(1).method());
            assertEquals(TestWorkspace.bucketPath(relative), sent.get(1).path());
        }
    }

    @Test
    void gzipsAnObjectAndInflatesItOnRead() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            S3Store store = new S3Store(config(s3));
            byte[] content = TestFiles.bytes("manifest ".repeat(2000));
            String relative = ObjectKeys.release("client", "1.0.0");

            int stored = store.putGzip(relative, content);

            String path = TestWorkspace.bucketPath(relative);
            byte[] storedBytes = s3.object(path);
            assertEquals(stored, storedBytes.length);
            assertTrue(stored < content.length, "compression shrinks the manifest: " + stored);
            assertEquals(0x1f, storedBytes[0] & 0xff, "a gzip member starts with its magic bytes");
            assertEquals(0x8b, storedBytes[1] & 0xff);
            FakeS3.Request sent = s3.requestsDetailed().get(0);
            assertEquals("gzip", sent.headers().get("content-encoding"));
            assertEquals(Sha256.ofBytes(storedBytes), sent.headers().get("x-amz-content-sha256"),
                    "the signature covers the bytes that are sent");
            assertTrue(sent.headers().get("authorization").contains("content-encoding"),
                    sent.headers().get("authorization"));
            assertEquals("gzip", s3.contentEncoding(path));

            assertArrayEquals(content, store.getBytes(relative), "a reader inflates it from the header alone");
        }
    }

    @Test
    void anonymousStoreSendsNoCredentials() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            s3.allowAnonymous(true);
            S3Store store = new S3Store(anonymous(s3));
            String relative = ObjectKeys.latest("client");
            s3.put(TestWorkspace.bucketPath(relative), TestFiles.bytes("{}"));

            assertArrayEquals(TestFiles.bytes("{}"), store.getBytes(relative));

            FakeS3.Request sent = s3.requestsDetailed().get(0);
            assertNull(sent.headers().get("authorization"), sent.headers().toString());
            assertNull(sent.headers().get("x-amz-date"), sent.headers().toString());
        }
    }

    @Test
    void downloadsToTheTargetPath(@TempDir Path dir) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            S3Store store = new S3Store(config(s3));
            byte[] content = TestFiles.bytes("bytes");
            String relative = ObjectKeys.blob(Sha256.ofBytes(content));
            s3.put(TestWorkspace.bucketPath(relative), content);
            Path target = dir.resolve("mods/deep/a.jar");

            store.download(relative, target);

            assertArrayEquals(content, Files.readAllBytes(target));
            try (var entries = Files.list(dir.resolve("mods/deep"))) {
                assertEquals(List.of(target), entries.toList(), "no temporary file is left behind");
            }
        }
    }

    @Test
    void missingObjectsAreReportedWithTheirKey() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            S3Store store = new S3Store(config(s3));
            String relative = ObjectKeys.latest("client");

            UserError error = assertThrows(UserError.class, () -> store.getBytes(relative));

            assertTrue(error.getMessage().contains("not found"), error.getMessage());
            assertTrue(error.getMessage().contains(TestWorkspace.bucketPath(relative)), error.getMessage());
            assertTrue(error.getMessage().contains("pack"), error.getMessage());
            assertTrue(error.getMessage().contains("NoSuchKey"),
                    "the store's error body is reported: " + error.getMessage());
        }
    }

    @Test
    void aRequestSignedWithAnotherSecretIsRefused() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            S3Store store = new S3Store(config(s3, "not-the-secret"));
            String relative = ObjectKeys.latest("client");
            s3.put(TestWorkspace.bucketPath(relative), TestFiles.bytes("{}"));

            UserError error = assertThrows(UserError.class, () -> store.getBytes(relative));

            assertTrue(error.getMessage().contains("access denied"), error.getMessage());
            assertTrue(error.getMessage().contains("SignatureDoesNotMatch"), error.getMessage());
        }
    }

    @Test
    void serverErrorsAreReportedWithTheirStatus() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            S3Store store = new S3Store(config(s3));
            String relative = ObjectKeys.latest("client");
            s3.fail("GET", TestWorkspace.bucketPath(relative), 500);

            UserError error = assertThrows(UserError.class, () -> store.getBytes(relative));

            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
            assertTrue(error.getMessage().contains("InternalError"), error.getMessage());
        }
    }

    @Test
    void aFailedUploadReportsTheStatusAndStoresNothing() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            S3Store store = new S3Store(config(s3));
            byte[] content = TestFiles.bytes("content");
            String relative = ObjectKeys.blob(Sha256.ofBytes(content));
            s3.fail("PUT", TestWorkspace.bucketPath(relative), 500);

            UserError error = assertThrows(UserError.class, () -> store.putBytes(relative, content));

            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
            assertNull(s3.object(TestWorkspace.bucketPath(relative)));
        }
    }

    @Test
    void existsIsFalseOnlyForAMissingObject() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            S3Store store = new S3Store(config(s3));
            String missing = ObjectKeys.blob(Sha256.ofBytes(TestFiles.bytes("missing")));
            String present = ObjectKeys.blob(Sha256.ofBytes(TestFiles.bytes("present")));
            s3.put(TestWorkspace.bucketPath(present), TestFiles.bytes("present"));

            assertFalse(store.exists(missing));
            assertTrue(store.exists(present));
        }
    }

    @Test
    void existsFailsOnAnErrorOtherThanNotFound() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            S3Store store = new S3Store(config(s3));
            String relative = ObjectKeys.latest("client");
            s3.fail("HEAD", TestWorkspace.bucketPath(relative), 500);

            UserError error = assertThrows(UserError.class, () -> store.exists(relative));

            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
        }
    }

    @Test
    void urlAvoidsADoubleSlashWhenThereIsNoPrefix() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            String hash = Sha256.ofBytes(TestFiles.bytes("content"));
            String objectKey = ObjectKeys.blob(hash);
            assertEquals(URI.create("https://s3.example.com/" + TestWorkspace.BUCKET + "/" + objectKey),
                    new S3Store(plain("https://s3.example.com")).uri(objectKey));

            Config prefixed = new Config("https://s3.example.com", TestWorkspace.REGION, TestWorkspace.BUCKET,
                    TestWorkspace.PREFIX, true, null, null, Config.DEFAULT_CONCURRENCY);
            assertEquals(URI.create("https://s3.example.com" + TestWorkspace.bucketPath(objectKey)),
                    new S3Store(prefixed).uri(objectKey));

            Config virtualHost = new Config("https://s3.example.com", TestWorkspace.REGION,
                    TestWorkspace.BUCKET, "", false, null, null, Config.DEFAULT_CONCURRENCY);
            assertEquals(URI.create("https://" + TestWorkspace.BUCKET + ".s3.example.com/" + objectKey),
                    new S3Store(virtualHost).uri(objectKey));
        }
    }

    @Test
    void anObjectThatCannotBeAddressedIsReportedWithoutAnException() {
        Config config = new Config("https://s3.example.com", TestWorkspace.REGION, "pack set", "",
                false, null, null, Config.DEFAULT_CONCURRENCY);
        S3Store store = new S3Store(config);

        UserError error = assertThrows(UserError.class, () -> store.getBytes(ObjectKeys.latest("client")));

        assertTrue(error.getMessage().contains("cannot address"), error.getMessage());
        assertTrue(error.getMessage().contains("unsupported URI"), error.getMessage());
    }

    @Test
    void objectKeysAreEscapedButSlashesSurvive() {
        URI escaped = new S3Store(plain("https://s3.example.com")).uri("metadata/a b/latest.json");
        assertEquals(URI.create("https://s3.example.com/" + TestWorkspace.BUCKET
                + "/metadata/a%20b/latest.json"), escaped);
        assertEquals("metadata/a b/latest.json", escaped.getPath().substring(
                ("/" + TestWorkspace.BUCKET + "/").length()), "slashes stay slashes");
    }
}

package io.github.deshark42.modpackupdater.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class S3ReadStoreTest {

    /** A reader whose URLs hang off the given CDN address, addressed the way the publisher writes them. */
    private static Config config(String readEndpoint) {
        return new Config("https://s3.example.com", readEndpoint, TestWorkspace.REGION, TestWorkspace.BUCKET,
                TestWorkspace.PREFIX, false, TestWorkspace.ACCESS_KEY, TestWorkspace.SECRET_KEY,
                Config.DEFAULT_CONCURRENCY);
    }

    private static String pathBelow(String base, String relative) {
        return base + "/" + TestWorkspace.PREFIX + "/" + relative;
    }

    @Test
    void addressesObjectsBelowTheReadEndpoint() {
        String key = ObjectKeys.latest("client");
        for (String base : List.of("https://cdn.example.com", "https://cdn.example.com/modpacks",
                "https://pack.cdn.example.com")) {
            assertEquals(pathBelow(base, key), new S3ReadStore(config(base)).uri(key).toString(),
                    "the object key hangs off the read address, with no bucket in between: " + base);
        }
    }

    @Test
    void doesNotDoubleTheSlashWhenNoPrefixIsConfigured() {
        Config config = new Config("https://s3.example.com", "https://cdn.example.com", TestWorkspace.REGION,
                TestWorkspace.BUCKET, "", false, null, null, Config.DEFAULT_CONCURRENCY);
        String key = ObjectKeys.latest("client");
        assertEquals("https://cdn.example.com/" + key, new S3ReadStore(config).uri(key).toString());
    }

    @Test
    void readsAnonymouslyAndAsksForTheBytesThatWereStored() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] content = TestFiles.bytes("through the cdn");
            s3.put(TestWorkspace.bucketPath("plain.txt"), content);
            s3.allowAnonymous(true);
            S3ReadStore store = new S3ReadStore(config(s3.url() + "/" + TestWorkspace.BUCKET));

            byte[] read = store.getBytes("plain.txt");

            assertArrayEquals(content, read);
            List<FakeS3.Request> requests = s3.requestsDetailed();
            assertEquals(1, requests.size(), requests.toString());
            FakeS3.Request request = requests.get(0);
            assertEquals("GET", request.method());
            assertEquals(TestWorkspace.bucketPath("plain.txt"), request.path());
            assertFalse(request.headers().containsKey("authorization"), request.toString());
            assertFalse(request.headers().containsKey("x-amz-date"), request.toString());
            assertEquals("identity", request.headers().get("accept-encoding"),
                    "a CDN must hand over the stored bytes, not a transcoded copy");
        }
    }

    @Test
    void aMissNamesTheUrlThatWasRead() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            s3.allowAnonymous(true);
            String base = s3.url() + "/" + TestWorkspace.BUCKET;
            S3ReadStore store = new S3ReadStore(config(base));

            UserError error = assertThrows(UserError.class, () -> store.getBytes(ObjectKeys.latest("client")));

            assertTrue(error.getMessage().startsWith("cannot read " + pathBelow(base, ObjectKeys.latest("client"))),
                    error.getMessage());
            assertTrue(error.getMessage().contains("not found"), error.getMessage());
            assertTrue(error.getMessage().contains("NoSuchKey"), error.getMessage());
        }
    }

    @Test
    void refusesADownloadThatWasTranscodedOnTheWay(@TempDir Path dir) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] content = TestFiles.bytes("chunk bytes");
            String relative = ObjectKeys.blob(Sha256.ofBytes(content));
            new S3Store(TestWorkspace.config(s3)).putGzip(relative, content);
            s3.allowAnonymous(true);
            String base = s3.url() + "/" + TestWorkspace.BUCKET;
            Path target = dir.resolve("chunk");

            UserError error = assertThrows(UserError.class,
                    () -> new S3ReadStore(config(base)).download(relative, target));

            assertTrue(error.getMessage().contains("gzip"), error.getMessage());
            assertEquals("cannot read " + pathBelow(base, relative) + ": the response is gzip-encoded,"
                    + " not the bytes that were stored", error.getMessage());
            assertFalse(Files.exists(target), "a response that is not the stored bytes writes nothing");
        }
    }
}

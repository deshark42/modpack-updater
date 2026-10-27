package io.github.deshark42.modpackupdater.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.model.ChunkTable;
import io.github.deshark42.modpackupdater.model.Chunking;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import io.github.deshark42.modpackupdater.model.Pointer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@ResourceLock(TestLogs.RESOURCE)
class PublishStoreTest {
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    @Test
    void anUnusedReleasePassesItsReadOnlyPrecondition() throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            String release = TestWorkspace.bucketPath(ObjectKeys.release("client", "new"));

            PublishStore.requireNewRelease(new S3Store(TestWorkspace.config(s3), CLOCK), "client", "new");

            assertEquals(List.of("HEAD " + release), s3.requests());
            assertEquals(0, s3.objectCount());
        }
    }

    @Test
    void anExistingReleaseFailsWithoutChangingAnyObject(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            String release = TestWorkspace.bucketPath(ObjectKeys.release("client", "existing"));
            String latest = TestWorkspace.bucketPath(ObjectKeys.latest("client"));
            byte[] original = TestFiles.bytes("immutable stored release");
            byte[] pointer = TestFiles.bytes(new Pointer("existing").toJson());
            s3.put(release, original);
            s3.put(latest, pointer);

            UserError error = assertThrows(UserError.class,
                    () -> PublishStore.requireNewRelease(new S3Store(TestWorkspace.config(s3), CLOCK), "client", "existing"));

            assertTrue(error.getMessage().contains("already exists"), error.getMessage());
            assertEquals(List.of("HEAD " + release), s3.requests());
            assertArrayEquals(original, s3.object(release));
            assertArrayEquals(pointer, s3.object(latest));
            assertEquals(2, s3.objectCount());
            assertFalse(Files.exists(Layout.knownBlobsFile(workspace)));
        }
    }

    @Test
    void aFailedReleaseProbeDoesNotAuthorizeAnyWrite(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            String release = TestWorkspace.bucketPath(ObjectKeys.release("client", "new"));
            s3.fail("HEAD", release, 500);

            UserError error = assertThrows(UserError.class,
                    () -> PublishStore.requireNewRelease(new S3Store(TestWorkspace.config(s3), CLOCK), "client", "new"));

            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
            assertEquals(List.of("HEAD " + release), s3.requests());
            assertEquals(0, s3.objectCount());
            assertFalse(Files.exists(Layout.knownBlobsFile(workspace)));
        }
    }

    @ParameterizedTest(name = "{0} upload: {1}")
    @CsvSource({"chunk,success", "index,success", "chunk,failure", "index,failure"})
    void anUploadConfirmsItsBytesOnlyAfterTheStoreAcceptsThem(String kind, String outcome,
            @TempDir Path workspace) throws IOException {
        boolean index = kind.equals("index");
        boolean fails = outcome.equals("failure");
        try (FakeS3 s3 = new FakeS3()) {
            byte[] content = TestFiles.bytes("upload bytes");
            byte[] bytes = index ? new ChunkTable(Chunking.DEFAULT, content.length,
                    List.of(Sha256.ofBytes(content))).bytes() : content;
            String hash = Sha256.ofBytes(bytes);
            String path = TestWorkspace.bucketPath(index ? ObjectKeys.index(hash) : ObjectKeys.blob(hash));
            BlobCache cache = BlobCache.open(workspace, TestWorkspace.config(s3), Duration.ofDays(1), CLOCK);
            PublishStore uploads = new PublishStore(new S3Store(TestWorkspace.config(s3), CLOCK), cache);
            if (fails) {
                s3.failPut(path);
                UserError error = assertThrows(UserError.class, () -> put(uploads, index, hash, bytes));
                assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
                assertNull(s3.object(path));
                assertFalse(cache.known(hash));
                assertFalse(BlobCache.open(workspace, TestWorkspace.config(s3), Duration.ofDays(1), CLOCK).known(hash));
                assertFalse(Files.exists(Layout.knownBlobsFile(workspace)));
                assertEquals(0, s3.objectCount());
            } else {
                put(uploads, index, hash, bytes);
                assertArrayEquals(bytes, s3.object(path));
                assertTrue(cache.known(hash));
                assertTrue(BlobCache.open(workspace, TestWorkspace.config(s3), Duration.ofDays(1), CLOCK).known(hash));
                assertEquals(1, s3.objectCount());
            }
            assertEquals(List.of("PUT " + path), s3.requests(), "only the requested object is uploaded");
        }
    }

    private static void put(PublishStore uploads, boolean index, String hash, byte[] bytes) {
        if (index) {
            uploads.putIndex(hash, bytes);
        } else {
            uploads.putChunk(hash, bytes);
        }
    }
}

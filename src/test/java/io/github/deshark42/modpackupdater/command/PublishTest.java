package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.log.Log;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishTest {

    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(printed, true, StandardCharsets.UTF_8);
    private final Log log = new Log(out, Log.Level.VERBOSE);

    private static void write(Path root, String name, String content) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void configure(Path workspace, FakeS3 s3) throws IOException {
        write(workspace, ".modpack-updater/config.json", "{\"endpoint\": \"" + s3.url()
                + "\", \"bucket\": \"pack\", \"prefix\": \"gtnh\", \"path_style\": true,"
                + " \"access_key\": \"AK\", \"secret_key\": \"SK\"}");
    }

    private static int indexOf(List<String> requests, String prefix) {
        for (int i = 0; i < requests.size(); i++) {
            if (requests.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void uploadsEveryFileAndPointsLatestAtTheRelease(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            write(workspace, "client/base/mods/b.jar", "A");
            write(workspace, "client/base/mmc-pack.json", "{}");

            Publish.run(workspace, "client", "2.9.0", false, log);

            String hash = Sha256.ofFile(workspace.resolve("client/base/mods/a.jar"));
            String blob = "PUT /pack/gtnh/blobs/" + hash.substring(0, 2) + "/" + hash;
            List<String> requests = s3.requests();
            assertTrue(requests.contains(blob), requests.toString());
            assertEquals(1, requests.stream().filter(r -> r.equals(blob)).count(),
                    "two files with the same content are one object");
            assertTrue(indexOf(requests, blob) < indexOf(requests, "PUT /pack/gtnh/metadata/client/releases/2.9.0.json"));
            assertTrue(indexOf(requests, "PUT /pack/gtnh/metadata/client/releases/2.9.0.json")
                    < indexOf(requests, "PUT /pack/gtnh/metadata/client/latest.json"));
            assertEquals(4, s3.objectCount());

            Manifest manifest = Manifest.parse(s3.text("/pack/gtnh/metadata/client/releases/2.9.0.json"), "release");
            assertEquals("2.9.0", manifest.releaseId());
            assertEquals(List.of("mmc-pack.json", "mods/a.jar", "mods/b.jar"), List.copyOf(manifest.files().keySet()));
            assertEquals(hash, manifest.files().get("mods/a.jar").hash());
            assertTrue(s3.text("/pack/gtnh/metadata/client/latest.json").contains("2.9.0"));
            assertTrue(printed.toString().contains("3 files"), printed.toString());
        }
    }

    @Test
    void reportsUploadProgressWhileItUploads(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            write(workspace, "client/base/mods/b.jar", "B");

            AtomicLong clock = new AtomicLong();
            Log ticking = new Log(out, Log.Level.NORMAL, false, () -> clock.addAndGet(2_000_000_000L));
            Publish.run(workspace, "client", "2.9.0", false, ticking);

            assertTrue(printed.toString().contains("1/2 blobs"), printed.toString());
            assertTrue(printed.toString().contains("2/2 blobs"), printed.toString());
        }
    }

    @Test
    void refusesToRewriteAPublishedRelease(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            Publish.run(workspace, "client", "2.9.0", false, log);
            int before = s3.objectCount();

            write(workspace, "client/base/mods/a.jar", "changed");
            UserError error = assertThrows(UserError.class, () -> Publish.run(workspace, "client", "2.9.0", false, log));

            assertTrue(error.getMessage().contains("already exists"), error.getMessage());
            assertEquals(before, s3.objectCount());
        }
    }

    @Test
    void dryRunUploadsNothingAndNeedsNoConnection(@TempDir Path workspace) throws IOException {
        write(workspace, "client/base/mods/a.jar", "A");

        Publish.run(workspace, "client", "2.9.0", true, log);

        assertTrue(printed.toString().contains("dry run"), printed.toString());
        assertTrue(printed.toString().contains("1 file,"), printed.toString());
    }

    @Test
    void reportsATagWithoutABaseDirectory(@TempDir Path workspace) {
        UserError error = assertThrows(UserError.class, () -> Publish.run(workspace, "client", "2.9.0", false, log));
        assertTrue(error.getMessage().contains("no such directory"), error.getMessage());
    }

    @Test
    void storesEachDistinctFileOnceAcrossReleases(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            Publish.run(workspace, "client", "2.9.0", false, log);

            write(workspace, "client/base/mods/b.jar", "B");
            Publish.run(workspace, "client", "2.9.1", false, log);

            long blobWrites = s3.requests().stream().filter(r -> r.startsWith("PUT /pack/gtnh/blobs/")).count();
            assertEquals(2, blobWrites, s3.requests().toString());
            String hash = Sha256.ofFile(workspace.resolve("client/base/mods/a.jar"));
            assertNotNull(s3.object("/pack/gtnh/blobs/" + hash.substring(0, 2) + "/" + hash));
            assertEquals(5, s3.objectCount());
        }
    }

    @Test
    void keepsTheTranscriptOfARunThatUploaded(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");

            Publish.run(workspace, "client", "2.9.0", false, log);

            List<Path> logs = logs(workspace);
            assertEquals(1, logs.size(), logs.toString());
            assertTrue(logs.get(0).getFileName().toString().endsWith("-publish-client.log"),
                    logs.get(0).toString());
            String text = Files.readString(logs.get(0));
            assertTrue(text.contains("publish client 2.9.0"), text);
            assertTrue(text.contains("upload  mods/a.jar"), text);
            assertTrue(text.contains("done: client 2.9.0 is now the latest"), text);
        }
    }

    @Test
    void writesNoTranscriptForADryRun(@TempDir Path workspace) throws IOException {
        write(workspace, "client/base/mods/a.jar", "A");

        Publish.run(workspace, "client", "2.9.0", true, log);

        assertFalse(Files.exists(Layout.logsDir(workspace)));
    }

    private static List<Path> logs(Path workspace) throws IOException {
        try (var files = Files.list(Layout.logsDir(workspace))) {
            return files.toList();
        }
    }
}

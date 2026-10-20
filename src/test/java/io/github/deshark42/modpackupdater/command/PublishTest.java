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
            write(workspace, "client/base/mods/c.jar", "C");

            ByteArrayOutputStream bar = new ByteArrayOutputStream();
            AtomicLong clock = new AtomicLong();
            Log ticking = new Log(out, new PrintStream(bar, true, StandardCharsets.UTF_8), Log.Level.NORMAL,
                    false, true, () -> clock.addAndGet(2_000_000_000L));
            Publish.run(workspace, "client", "2.9.0", false, ticking);

            String frames = bar.toString();
            assertTrue(frames.contains("2/3 blobs"), frames);
            assertTrue(frames.endsWith("\r\u001b[K"), "the bar is erased when the transfer ends: " + frames);
            assertFalse(frames.contains("3/3 blobs"), "the last file does not draw a bar: " + frames);
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
        assertFalse(Files.exists(Layout.knownBlobsFile(workspace)), "a dry run writes no cache");
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
            assertTrue(text.contains("upload   mods/a.jar"), text);
            assertTrue(text.contains("done: client 2.9.0 is now the latest"), text);
        }
    }

    @Test
    void reportsWhatItUploadedAndWhatItSkipped(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            write(workspace, "client/base/mods/b.jar", "B");
            Publish.run(workspace, "client", "2.9.0", false, log);
            printed.reset();

            write(workspace, "client/base/mods/c.jar", "C");
            Publish.run(workspace, "client", "2.9.1", false, log);

            String text = printed.toString();
            assertTrue(text.contains("plan     1 blob to upload, 2 blobs already there"), text);
            assertTrue(text.contains("upload   1 blob"), text);
            assertTrue(text.contains("skip     2 blobs"), text);
        }
    }

    @Test
    void writesNoTranscriptForADryRun(@TempDir Path workspace) throws IOException {
        write(workspace, "client/base/mods/a.jar", "A");

        Publish.run(workspace, "client", "2.9.0", true, log);

        assertFalse(Files.exists(Layout.logsDir(workspace)));
    }

    @Test
    void takesOverlayFilesAndPrefersThemOverBase(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "base");
            write(workspace, "client/base/mods/b.jar", "B");
            write(workspace, "client/overlay/mods/a.jar", "overlay");
            write(workspace, "client/overlay/config/mine.json", "{}");

            Publish.run(workspace, "client", "2.9.0", false, log);

            Manifest manifest = Manifest.parse(s3.text("/pack/gtnh/metadata/client/releases/2.9.0.json"), "release");
            assertEquals(List.of("config/mine.json", "mods/a.jar", "mods/b.jar"),
                    List.copyOf(manifest.files().keySet()));
            String hash = Sha256.ofFile(workspace.resolve("client/overlay/mods/a.jar"));
            assertEquals(hash, manifest.files().get("mods/a.jar").hash(), "overlay wins the same path");
            assertEquals("overlay", new String(s3.object(blobKey(hash)), StandardCharsets.UTF_8));
            String text = printed.toString();
            assertTrue(text.contains("replace  mods/a.jar"), text);
            assertTrue(text.contains("add      config/mine.json"), text);
        }
    }

    @Test
    void removesTheBaseFilesRemoveTxtMatches(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            write(workspace, "client/base/mods/example-mod-1.0.jar", "B");
            write(workspace, "client/base/config/secret.cfg", "C");
            write(workspace, "client/base/config/deep/other.cfg", "D");
            Files.writeString(Layout.removeFile(workspace, "client"),
                    "example-mod-*.jar\n/config/\nghost.jar\n");

            Publish.run(workspace, "client", "2.9.0", false, log);

            Manifest manifest = Manifest.parse(s3.text("/pack/gtnh/metadata/client/releases/2.9.0.json"), "release");
            assertEquals(List.of("mods/a.jar"), List.copyOf(manifest.files().keySet()));
            String text = printed.toString();
            assertTrue(text.contains("remove   3 files"), text);
            assertTrue(text.contains("stale    1 rule"), text);
            assertTrue(text.contains("ghost.jar"), text);
            assertTrue(text.contains("merge    0 files added, 0 files replaced"), text);
        }
    }

    @Test
    void removesNothingFromTheOverlay(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/example-mod.jar", "base");
            write(workspace, "client/base/mods/other.jar", "B");
            write(workspace, "client/overlay/mods/example-mod.jar", "mine");
            Files.writeString(Layout.removeFile(workspace, "client"), "example-mod.jar\n");

            Publish.run(workspace, "client", "2.9.0", false, log);

            Manifest manifest = Manifest.parse(s3.text("/pack/gtnh/metadata/client/releases/2.9.0.json"), "release");
            assertEquals(List.of("mods/example-mod.jar", "mods/other.jar"),
                    List.copyOf(manifest.files().keySet()));
            assertEquals(Sha256.ofFile(workspace.resolve("client/overlay/mods/example-mod.jar")),
                    manifest.files().get("mods/example-mod.jar").hash());
        }
    }

    @Test
    void dryRunReportsTheSameMergeAsTheRealPublish(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            write(workspace, "client/base/mods/a.jar", "base");
            write(workspace, "client/overlay/mods/a.jar", "overlay");
            write(workspace, "client/overlay/config/mine.json", "{}");
            Files.writeString(Layout.removeFile(workspace, "client"), "ghost.jar\n");

            Log quiet = new Log(out, Log.Level.NORMAL);
            Publish.run(workspace, "client", "2.9.0", true, quiet);
            String dry = line(printed.toString(), "merge");
            printed.reset();

            configure(workspace, s3);
            Publish.run(workspace, "client", "2.9.0", false, quiet);
            String real = line(printed.toString(), "merge");

            assertNotNull(dry, "a dry run reports the merge");
            assertEquals(dry, real);
        }
    }

    @Test
    void refusesABadRemoveRuleBeforeTouchingTheBucket(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            Files.writeString(Layout.removeFile(workspace, "client"), "../escape.jar\n");

            UserError error = assertThrows(UserError.class,
                    () -> Publish.run(workspace, "client", "2.9.0", false, log));

            assertTrue(error.getMessage().contains("leaves the tag directory"), error.getMessage());
            assertTrue(s3.requests().isEmpty(), s3.requests().toString());
        }
    }

    @Test
    void namesEachStageAndEndsWithASummary(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            Publish.run(workspace, "client", "2.9.0", false, log);

            List<String> lines = printed.toString().lines().toList();
            int inputs = lines.indexOf("inputs");
            int transfer = lines.indexOf("transfer");
            int commit = lines.indexOf("commit");
            int done = lines.indexOf(lines.stream().filter(line -> line.startsWith("done: "))
                    .findFirst().orElseThrow());
            assertTrue(inputs > 0 && transfer > inputs && commit > transfer && done > commit, lines.toString());
            assertTrue(lines.get(inputs - 1).isEmpty(), "a stage is set off by a blank line: " + lines);
            assertTrue(lines.get(done + 1).startsWith("  contents  "), lines.toString());
            assertTrue(lines.get(lines.size() - 1).startsWith("  log       "), lines.toString());
        }
    }


    @Test
    void asksTheBucketOnlyAboutBlobsTheCacheDoesNotKnow(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            Publish.run(workspace, "client", "2.9.0", false, log);
            String hashA = Sha256.ofFile(workspace.resolve("client/base/mods/a.jar"));
            int before = s3.requests().size();

            write(workspace, "client/base/mods/b.jar", "B");
            Publish.run(workspace, "client", "2.9.1", false, log);
            List<String> second = s3.requests().subList(before, s3.requests().size());

            assertFalse(second.contains("HEAD " + blobKey(hashA)), second.toString());
            String hashB = Sha256.ofFile(workspace.resolve("client/base/mods/b.jar"));
            assertEquals(1, second.stream().filter(request -> request.equals("HEAD " + blobKey(hashB))).count(),
                    second.toString());
        }
    }

    @Test
    void remembersBlobsItFoundInTheBucket(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            String hash = Sha256.ofFile(workspace.resolve("client/base/mods/a.jar"));
            s3.put(blobKey(hash), "A".getBytes(StandardCharsets.UTF_8));

            Publish.run(workspace, "client", "2.9.0", false, log);

            assertTrue(cache(workspace).contains(hash), cache(workspace));
            assertFalse(s3.requests().stream().anyMatch(request -> request.startsWith("PUT " + blobKey(hash))),
                    s3.requests().toString());
            assertTrue(printed.toString().contains("skip     1 blob"), printed.toString());
        }
    }

    @Test
    void keepsConfirmedBlobsWhenALaterStepFails(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            String hash = Sha256.ofFile(workspace.resolve("client/base/mods/a.jar"));
            s3.put(blobKey(hash), "A".getBytes(StandardCharsets.UTF_8));
            s3.failPut("/pack/gtnh/metadata/client/releases/2.9.0.json");

            assertThrows(UserError.class, () -> Publish.run(workspace, "client", "2.9.0", false, log));

            assertTrue(cache(workspace).contains(hash), cache(workspace));
        }
    }

    @Test
    void keepsUploadedBlobsWhenALaterStepFails(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            String hash = Sha256.ofFile(workspace.resolve("client/base/mods/a.jar"));
            s3.failPut("/pack/gtnh/metadata/client/releases/2.9.0.json");

            assertThrows(UserError.class, () -> Publish.run(workspace, "client", "2.9.0", false, log));

            assertNotNull(s3.object(blobKey(hash)));
            assertTrue(cache(workspace).contains(hash), cache(workspace));
        }
    }

    @Test
    void doesNotRememberAFailedUpload(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            String hash = Sha256.ofFile(workspace.resolve("client/base/mods/a.jar"));
            s3.failPut(blobKey(hash));

            assertThrows(UserError.class, () -> Publish.run(workspace, "client", "2.9.0", false, log));
            assertFalse(cache(workspace).contains(hash), cache(workspace));

            s3.clearFailures();
            Publish.run(workspace, "client", "2.9.0", false, log);
            assertTrue(cache(workspace).contains(hash), cache(workspace));
            assertNotNull(s3.object(blobKey(hash)));
        }
    }

    @Test
    void givesUpTheCacheWhenTheBucketChanges(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            Publish.run(workspace, "client", "2.9.0", false, log);
            String hash = Sha256.ofFile(workspace.resolve("client/base/mods/a.jar"));
            int before = s3.requests().size();

            write(workspace, ".modpack-updater/config.json", "{\"endpoint\": \"" + s3.url()
                    + "\", \"bucket\": \"other\", \"prefix\": \"gtnh\", \"path_style\": true,"
                    + " \"access_key\": \"AK\", \"secret_key\": \"SK\"}");
            Publish.run(workspace, "client", "2.9.1", false, log);
            List<String> second = s3.requests().subList(before, s3.requests().size());

            String otherBlob = "/other/gtnh/blobs/" + hash.substring(0, 2) + "/" + hash;
            assertTrue(second.contains("HEAD " + otherBlob), second.toString());
        }
    }

    @Test
    void rebuildsACorruptedCache(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            write(workspace, "client/base/mods/a.jar", "A");
            Publish.run(workspace, "client", "2.9.0", false, log);
            String hash = Sha256.ofFile(workspace.resolve("client/base/mods/a.jar"));
            Files.writeString(Layout.knownBlobsFile(workspace), "not a cache\n", StandardCharsets.UTF_8);
            int before = s3.requests().size();

            Publish.run(workspace, "client", "2.9.1", false, log);
            List<String> second = s3.requests().subList(before, s3.requests().size());

            assertTrue(second.contains("HEAD " + blobKey(hash)), second.toString());
            assertTrue(cache(workspace).contains(hash), cache(workspace));
        }
    }

    @Test
    void remembersEveryBlobUploadedInParallel(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            configure(workspace, s3);
            for (int i = 0; i < 8; i++) {
                write(workspace, "client/base/mods/" + i + ".jar", "content-" + i);
            }

            Publish.run(workspace, "client", "2.9.0", false, log);

            String cache = cache(workspace);
            for (int i = 0; i < 8; i++) {
                String hash = Sha256.ofFile(workspace.resolve("client/base/mods/" + i + ".jar"));
                assertTrue(cache.contains(hash), cache);
            }
        }
    }

    private static String cache(Path workspace) throws IOException {
        Path file = Layout.knownBlobsFile(workspace);
        return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }

    private static String blobKey(String hash) {
        return "/pack/gtnh/blobs/" + hash.substring(0, 2) + "/" + hash;
    }

    private static String line(String text, String marker) {
        for (String candidate : text.split("\n")) {
            if (candidate.contains(marker)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Path> logs(Path workspace) throws IOException {
        try (var files = Files.list(Layout.logsDir(workspace))) {
            return files.toList();
        }
    }
}

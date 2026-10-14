package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.log.Log;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Pointer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateTest {

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

    /** Replaces the tag's base tree with the given files, the way a publisher would by hand, then publishes. */
    private void publish(Path publisher, String tag, String releaseId, Map<String, String> files) throws IOException {
        Path base = publisher.resolve(tag).resolve("base");
        if (Files.isDirectory(base)) {
            try (var walk = Files.walk(base)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        for (Map.Entry<String, String> file : files.entrySet()) {
            write(publisher, tag + "/base/" + file.getKey(), file.getValue());
        }
        Publish.run(publisher, tag, releaseId, false, log);
    }

    private static String applied(Path workspace, String tag) {
        return Pointer.read(Layout.currentFile(workspace, tag)).releaseId();
    }

    @Test
    void firstUpdateLaysDownEveryTrackedFile(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            configure(publisher, s3);
            publish(publisher, "client", "2.9.0",
                    Map.of("mods/a.jar", "A", "libraries/lwjgl.jar", "L", "mmc-pack.json", "{}"));
            Path player = root.resolve("player");
            configure(player, s3);

            Update.run(player, "client", false, log);

            assertEquals("A", Files.readString(player.resolve("mods/a.jar")));
            assertEquals("L", Files.readString(player.resolve("libraries/lwjgl.jar")));
            assertEquals("{}", Files.readString(player.resolve("mmc-pack.json")));
            assertEquals("2.9.0", applied(player, "client"));
            assertTrue(Files.isRegularFile(Layout.releaseFile(player, "client", "2.9.0")));

            printed.reset();
            Update.run(player, "client", false, log);
            assertTrue(printed.toString().contains("already at 2.9.0"), printed.toString());
        }
    }

    @Test
    void replacesAndAddsWhatChangedAndBacksUpTheOldFiles(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            configure(publisher, s3);
            publish(publisher, "client", "2.9.0",
                    Map.of("mods/a.jar", "A", "mods/b.jar", "B", "config/x.cfg", "X"));
            Path player = root.resolve("player");
            configure(player, s3);
            Update.run(player, "client", false, log);
            FileTime untouched = Files.getLastModifiedTime(player.resolve("config/x.cfg"));

            publish(publisher, "client", "2.9.1", Map.of("mods/a.jar", "A2", "mods/c.jar", "C", "config/x.cfg", "X"));
            printed.reset();
            Update.run(player, "client", false, log);

            assertEquals("A2", Files.readString(player.resolve("mods/a.jar")));
            assertEquals("C", Files.readString(player.resolve("mods/c.jar")));
            assertFalse(Files.exists(player.resolve("mods/b.jar")));
            assertEquals(untouched, Files.getLastModifiedTime(player.resolve("config/x.cfg")),
                    "a file whose hash did not change is not rewritten");
            Path backup = Layout.backupDir(player, "client", "2.9.0");
            assertEquals("A", Files.readString(backup.resolve("mods/a.jar")));
            assertEquals("B", Files.readString(backup.resolve("mods/b.jar")));
            assertEquals("2.9.1", applied(player, "client"));
            assertTrue(printed.toString().contains("add     mods/c.jar"), printed.toString());
            assertTrue(printed.toString().contains("replace mods/a.jar"), printed.toString());
            assertTrue(printed.toString().contains("delete  mods/b.jar"), printed.toString());
        }
    }

    @Test
    void leavesFilesAloneWhenOnlyTheirLocalCopyChanged(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            configure(publisher, s3);
            publish(publisher, "client", "2.9.0", Map.of("config/x.cfg", "X", "mods/b.jar", "B"));
            Path player = root.resolve("player");
            configure(player, s3);
            Update.run(player, "client", false, log);

            write(player, "config/x.cfg", "my own settings");
            Files.delete(player.resolve("mods/b.jar"));

            publish(publisher, "client", "2.9.1", Map.of("config/x.cfg", "X", "mods/b.jar", "B", "mods/c.jar", "C"));
            Update.run(player, "client", false, log);

            assertEquals("my own settings", Files.readString(player.resolve("config/x.cfg")));
            assertFalse(Files.exists(player.resolve("mods/b.jar")), "a file the player removed is not put back");
            assertEquals("C", Files.readString(player.resolve("mods/c.jar")));
        }
    }

    @Test
    void dryRunReportsTheChangesAndWritesNothing(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            configure(publisher, s3);
            publish(publisher, "client", "2.9.0", Map.of("mods/a.jar", "A"));
            Path player = root.resolve("player");
            configure(player, s3);
            Update.run(player, "client", false, log);

            publish(publisher, "client", "2.9.1", Map.of("mods/a.jar", "A2"));
            printed.reset();
            Update.run(player, "client", true, log);

            assertTrue(printed.toString().contains("dry run"), printed.toString());
            assertTrue(printed.toString().contains("replace mods/a.jar"), printed.toString());
            assertEquals("A", Files.readString(player.resolve("mods/a.jar")));
            assertEquals("2.9.0", applied(player, "client"));

            Update.run(player, "client", false, log);
            assertEquals("A2", Files.readString(player.resolve("mods/a.jar")));
            assertEquals("2.9.1", applied(player, "client"));
        }
    }

    @Test
    void reportsAPointerWithoutItsManifest(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            configure(publisher, s3);
            publish(publisher, "client", "2.9.0", Map.of("mods/a.jar", "A"));
            Path player = root.resolve("player");
            configure(player, s3);
            Update.run(player, "client", false, log);
            Files.delete(Layout.releaseFile(player, "client", "2.9.0"));

            UserError error = assertThrows(UserError.class, () -> Update.run(player, "client", false, log));
            assertTrue(error.getMessage().contains("is missing"), error.getMessage());
        }
    }

    @Test
    void reportsATagThatWasNeverPublished(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path player = root.resolve("player");
            configure(player, s3);

            UserError error = assertThrows(UserError.class, () -> Update.run(player, "client", false, log));
            assertTrue(error.getMessage().contains("not found"), error.getMessage());
        }
    }

    @Test
    void keepsEachTagInItsOwnStateDirectory(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            configure(publisher, s3);
            publish(publisher, "client", "1.0", Map.of("mods/a.jar", "A"));
            publish(publisher, "client-extra", "1.0", Map.of("shaderpacks/s.zip", "S"));
            Path player = root.resolve("player");
            configure(player, s3);

            Update.run(player, "client", false, log);
            Update.run(player, "client-extra", false, log);

            assertEquals("1.0", applied(player, "client"));
            assertEquals("1.0", applied(player, "client-extra"));
            assertEquals("A", Files.readString(player.resolve("mods/a.jar")));
            assertEquals("S", Files.readString(player.resolve("shaderpacks/s.zip")));
        }
    }
}

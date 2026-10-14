package io.github.deshark42.modpackupdater.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.model.Layout;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogFileTest {

    @Test
    void keepsOneFilePerRunAndNeverOverwrites(@TempDir Path workspace) throws IOException {
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        Log log = new Log(new PrintStream(printed, true, StandardCharsets.UTF_8), Log.Level.QUIET);
        log.headline("update client 2.9.0");

        LogFile.keep(workspace, "update-client", log);
        LogFile.keep(workspace, "update-client", log);

        List<Path> files;
        try (var listing = Files.list(Layout.logsDir(workspace))) {
            files = listing.toList();
        }
        assertEquals(2, files.size(), files.toString());
        for (Path file : files) {
            assertTrue(Files.readString(file).contains("update client 2.9.0"), file.toString());
        }
        assertEquals("", printed.toString(), "quiet is about the console, not about the file");
    }

    @Test
    void saysWhereItPutTheLog(@TempDir Path workspace) {
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        Log log = new Log(new PrintStream(printed, true, StandardCharsets.UTF_8), Log.Level.NORMAL);

        LogFile.keep(workspace, "publish-client", log);

        assertTrue(printed.toString().contains("log       .modpack-updater/logs/"), printed.toString());
        assertTrue(printed.toString().contains("-publish-client.log"), printed.toString());
    }

    @Test
    void namesItsOwnPathInTheSummaryItSaves(@TempDir Path workspace) throws IOException {
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        Log log = new Log(new PrintStream(printed, true, StandardCharsets.UTF_8), Log.Level.NORMAL);
        log.headline("publish client 2.9.0");

        LogFile.keep(workspace, "publish-client", log);

        List<Path> files;
        try (var listing = Files.list(Layout.logsDir(workspace))) {
            files = listing.toList();
        }
        assertEquals(1, files.size(), files.toString());
        String text = Files.readString(files.get(0));
        assertTrue(text.contains("publish client 2.9.0"), text);
        assertTrue(text.contains("-publish-client.log"), "the saved log says where it is: " + text);
    }
}

package io.github.deshark42.modpackupdater.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.LogSink;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogFileTest {

    @Test
    void keepsOneFilePerRunAndNeverOverwrites(@TempDir Path workspace) throws IOException {
        LogSink sink = new LogSink();
        Log log = sink.log(Log.Level.QUIET);
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
        assertEquals("", sink.outText(), "quiet is about the console, not about the file");
    }

    @Test
    void saysWhereItPutTheLog(@TempDir Path workspace) {
        LogSink sink = new LogSink();

        LogFile.keep(workspace, "publish-client", sink.log());

        String directory = Log.relativeTo(workspace, Layout.logsDir(workspace));
        assertTrue(sink.outText().contains("  log       " + directory + "/"), sink.outText());
        assertTrue(sink.outText().contains("-publish-client.log"), sink.outText());
    }

    @Test
    void namesItsOwnPathInTheSummaryItSaves(@TempDir Path workspace) throws IOException {
        LogSink sink = new LogSink();
        Log log = sink.log();
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

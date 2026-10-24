package io.github.deshark42.modpackupdater.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.LogSink;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogTest {

    private final LogSink sink = new LogSink();

    @Test
    void putsVerbsAndNotesInTheirOwnColumns() {
        Log log = sink.log();
        log.headline("update client 2.9.0 -> 2.9.1");
        log.step("plan", "12 added, 3 replaced, 1 deleted", "48.2 MiB to download");
        log.step("download", "1234/5678 files", "3 B to download");

        String[] lines = sink.outText().split(System.lineSeparator());
        assertEquals("update client 2.9.0 -> 2.9.1", lines[0]);
        assertEquals(lines[1].indexOf("12 added"), lines[2].indexOf("1234/5678"), "verbs line up");
        assertEquals(lines[1].indexOf("48.2"), lines[2].indexOf("3 B"), "notes line up");
    }

    @Test
    void printsOneItemPerFileOnlyWhenAskedFor() {
        new Log(sink.out()).item("add", "mods/a.jar");
        assertEquals("", sink.outText());

        Log verbose = sink.log(Log.Level.VERBOSE);
        verbose.item("add", "mods/a.jar");
        verbose.item("replace", "config/x.cfg");
        assertEquals(String.join(System.lineSeparator(),
                "    add      mods/a.jar",
                "    replace  config/x.cfg",
                ""), sink.outText());
    }

    @Test
    void quietKeepsOnlyTheClosingLine() {
        Log log = sink.log(Log.Level.QUIET, () -> 0L);
        log.headline("update client");
        log.step("plan", "1 added", "");
        log.summary("kept", "nothing to see");
        log.item("add", "mods/a.jar");
        log.done("client is now at 2.9.1");

        assertEquals("done: client is now at 2.9.1, 0.0s" + System.lineSeparator(), sink.outText());
    }

    @Test
    void aDryRunEndsLikeARunThatHappened() {
        new Log(sink.out(), sink.out(), Log.Level.QUIET, false, false, () -> 0L).dryRun();

        assertEquals("dry run: nothing was written, 0.0s" + System.lineSeparator(), sink.outText());
    }

    @Test
    void showsPathsRelativeToTheWorkspace() {
        Path workspace = Path.of("/ws");

        assertEquals("client/base", Log.relativeTo(workspace, Path.of("/ws/client/base")));
    }

    @Test
    void countsFilesWithTheirPlural() {
        assertEquals("1 file", Log.count(1, "file"));
        assertEquals("3 files", Log.count(3, "file"));
        assertEquals("0 blobs", Log.count(0, "blob"));
    }

    @Test
    void recordsTheFileLinesTheConsoleWasNotAskedToShow() {
        Log log = sink.log(Log.Level.NORMAL);

        log.headline("publish client 2.9.0");
        log.step("upload", "3 blobs");
        log.item("upload", "mods/a.jar");
        log.done("client 2.9.0 is now the latest");

        List<String> transcript = log.transcript();
        assertEquals("publish client 2.9.0", transcript.get(0));
        assertTrue(transcript.contains("  upload   3 blobs"), transcript.toString());
        assertTrue(transcript.contains("    upload   mods/a.jar"), transcript.toString());
        assertTrue(transcript.get(3).startsWith("done: client 2.9.0 is now the latest, "), transcript.toString());
        assertFalse(sink.outText().contains("mods/a.jar"), "NORMAL prints no file lines");
    }

    @Test
    void opensAStageWithAHeadingAndClosesWithASummary() {
        Log log = new Log(sink.out(), Log.Level.NORMAL, false, () -> 0L);
        log.headline("publish client 1.0.0");
        log.phase("transfer");
        log.step("upload", "2 blobs");
        log.done("client 1.0.0 is now the latest");
        log.summary("uploaded", "2 blobs, 7 B in 0.4s (17.5 B/s)");

        String[] lines = sink.outText().split(System.lineSeparator());
        assertEquals("publish client 1.0.0", lines[0]);
        assertEquals("", lines[1], "a stage is set off by a blank line");
        assertEquals("transfer", lines[2]);
        assertEquals("  upload   2 blobs", lines[3]);
        assertEquals("done: client 1.0.0 is now the latest, " + Log.duration(0), lines[4]);
        assertEquals("  uploaded  2 blobs, 7 B in 0.4s (17.5 B/s)", lines[5]);

        List<String> transcript = log.transcript();
        assertEquals("", transcript.get(1));
        assertEquals("transfer", transcript.get(2));
        assertEquals("  uploaded  2 blobs, 7 B in 0.4s (17.5 B/s)", transcript.get(5));
    }

    @Test
    void recordsTheSummaryInTheTranscriptEvenWhenQuiet() {
        Log log = sink.log(Log.Level.QUIET);

        log.summary("uploaded", "2 blobs");

        assertEquals("", sink.outText(), "QUIET prints no summary lines");
        assertEquals(List.of("  uploaded  2 blobs"), log.transcript());
    }

    @Test
    void showsDurationsTheWayTheSummariesWant() {
        assertEquals("0.4s", Log.duration(400_000_000L));
        assertEquals("12.0s", Log.duration(12_000_000_000L));
    }
}

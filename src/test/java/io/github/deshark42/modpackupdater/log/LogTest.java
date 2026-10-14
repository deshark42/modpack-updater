package io.github.deshark42.modpackupdater.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.fs.Sizes;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LogTest {

    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(printed, true, StandardCharsets.UTF_8);

    @Test
    void putsVerbsAndNotesInTheirOwnColumns() {
        Log log = new Log(out);
        log.headline("update client 2.9.0 -> 2.9.1");
        log.step("plan", "12 added, 3 replaced, 1 deleted", "48.2 MiB to download");
        log.step("download", "1234/5678 files");
        log.done("client is now at 2.9.1");

        String[] lines = printed.toString().split(System.lineSeparator());
        assertEquals("update client 2.9.0 -> 2.9.1", lines[0]);
        assertTrue(lines[1].startsWith("  plan     12 added, 3 replaced, 1 deleted"), lines[1]);
        assertEquals(51, lines[1].indexOf("48.2 MiB to download"), lines[1]);
        assertEquals("  download 1234/5678 files", lines[2]);
        assertTrue(lines[3].startsWith("done: client is now at 2.9.1, "), lines[3]);
    }

    @Test
    void printsOneItemPerFileOnlyWhenAskedFor() {
        new Log(out).item("add", "mods/a.jar");
        assertEquals("", printed.toString());

        Log verbose = new Log(out, Log.Level.VERBOSE);
        verbose.item("add", "mods/a.jar");
        verbose.item("replace", "config/x.cfg");
        assertEquals(String.join(System.lineSeparator(),
                "    add      mods/a.jar",
                "    replace  config/x.cfg",
                ""), printed.toString());
    }

    @Test
    void quietKeepsOnlyTheClosingLine() {
        Log log = new Log(out, Log.Level.QUIET);
        log.headline("update client");
        log.step("plan", "1 added", "");
        log.summary("kept", "nothing to see");
        log.item("add", "mods/a.jar");
        log.done("client is now at 2.9.1");

        assertEquals(1, printed.toString().lines().count(), printed.toString());
        assertTrue(printed.toString().startsWith("done: client is now at 2.9.1, "), printed.toString());
    }

    @Test
    void aDryRunEndsLikeARunThatHappened() {
        new Log(out, Log.Level.QUIET).dryRun();

        assertTrue(printed.toString().startsWith("dry run: nothing was written, "), printed.toString());
    }

    @Test
    void drawsNoBarWhenThereIsNoTerminalToDrawItOn() {
        AtomicLong clock = new AtomicLong();
        Log log = new Log(out, out, Log.Level.NORMAL, false, false, clock::get);

        clock.addAndGet(1_000_000_000L);
        log.progress("upload", 1, 3, 4, "blob");

        assertEquals("", printed.toString());
        assertTrue(log.transcript().isEmpty(), log.transcript().toString());
    }

    @Test
    void drawsABarInPlaceAndErasesItBeforeTheNextLine() {
        ByteArrayOutputStream bar = new ByteArrayOutputStream();
        AtomicLong clock = new AtomicLong();
        Log log = new Log(out, new PrintStream(bar, true, StandardCharsets.UTF_8), Log.Level.NORMAL,
                false, true, clock::get);

        clock.addAndGet(400_000_000L);
        log.progress("upload", 1, 4, 262_144, "blob");
        assertEquals("", bar.toString(), "a transfer that just started gets no bar yet");

        clock.addAndGet(600_000_000L);
        log.progress("upload", 2, 4, 1_048_576, "blob");
        String frame = bar.toString();
        assertTrue(frame.startsWith("\r"), frame);
        assertTrue(frame.contains("#"), frame);
        assertTrue(frame.contains("-"), frame);
        assertTrue(frame.contains(" 50% \u00b7 2/4 blobs \u00b7 "), frame);
        assertTrue(frame.contains("MiB/s"), frame);
        assertTrue(frame.contains("ETA 0:01"), frame);

        bar.reset();
        log.step("upload", "4 blobs");
        assertEquals("\r\u001b[K", bar.toString(), "the step erases the bar");
        assertTrue(printed.toString().endsWith("  upload   4 blobs" + System.lineSeparator()),
                printed.toString());
        assertFalse(log.transcript().stream().anyMatch(line -> line.contains("2/4")),
                log.transcript().toString());
    }

    @Test
    void erasesTheBarOnlyWhileItIsOnScreen() {
        ByteArrayOutputStream bar = new ByteArrayOutputStream();
        AtomicLong clock = new AtomicLong();
        Log log = new Log(out, new PrintStream(bar, true, StandardCharsets.UTF_8), Log.Level.NORMAL,
                false, true, clock::get);

        log.clearProgress();
        assertEquals("", bar.toString(), "there was nothing to erase");

        clock.addAndGet(400_000_000L);
        log.progress("upload", 1, 3, 1024, "blob");
        clock.addAndGet(600_000_000L);
        log.progress("upload", 2, 3, 2048, "blob");
        assertTrue(bar.toString().contains("2/3"), bar.toString());

        bar.reset();
        log.clearProgress();
        assertEquals("\r\u001b[K", bar.toString());
        bar.reset();
        log.clearProgress();
        assertEquals("", bar.toString(), "the bar is erased once");
    }

    @Test
    void quietRunsDrawNoBar() {
        ByteArrayOutputStream bar = new ByteArrayOutputStream();
        AtomicLong clock = new AtomicLong();
        Log log = new Log(out, new PrintStream(bar, true, StandardCharsets.UTF_8), Log.Level.QUIET,
                false, true, clock::get);

        clock.addAndGet(400_000_000L);
        log.progress("upload", 1, 3, 1024, "blob");
        clock.addAndGet(600_000_000L);
        log.progress("upload", 2, 3, 2048, "blob");

        assertEquals("", bar.toString());
    }

    @Test
    void coloursFileVerbsButNotOtherLines() {
        Log log = new Log(out, Log.Level.VERBOSE, true);
        log.item("add", "mods/a.jar");
        log.item("delete", "mods/b.jar");
        log.step("scan", "client/base");
        log.done("client is now at 2.9.1");

        String text = printed.toString();
        assertTrue(text.contains("\u001b[32madd      \u001b[0mmods/a.jar"), text);
        assertTrue(text.contains("\u001b[31mdelete   \u001b[0mmods/b.jar"), text);
        assertTrue(text.contains("  scan     client/base"), text);
        assertTrue(text.contains("\u001b[1mdone\u001b[0m: client is now at 2.9.1"), text);
    }

    @Test
    void pathsAreShownRelativeToTheWorkspace() {
        Path workspace = Path.of("/ws");
        assertEquals("client/base", Log.relativeTo(workspace, Path.of("/ws/client/base")));
        assertEquals("1 file", Log.count(1, "file"));
        assertEquals("3 files", Log.count(3, "file"));
        assertEquals("0 blobs", Log.count(0, "blob"));
    }

    @Test
    void recordsTheFileLinesTheConsoleWasNotAskedToShow() {
        Log log = new Log(out, Log.Level.NORMAL, true);

        log.headline("publish client 2.9.0");
        log.step("upload", "3 blobs");
        log.item("upload", "mods/a.jar");
        log.done("client 2.9.0 is now the latest");

        List<String> transcript = log.transcript();
        assertEquals("publish client 2.9.0", transcript.get(0));
        assertTrue(transcript.contains("  upload   3 blobs"), transcript.toString());
        assertTrue(transcript.contains("    upload   mods/a.jar"), transcript.toString());
        assertTrue(transcript.get(3).startsWith("done: client 2.9.0 is now the latest, "),
                transcript.toString());
        assertFalse(printed.toString().contains("mods/a.jar"), "NORMAL prints no file lines");
    }

    @Test
    void keepsWorkInFlightOutOfTheTranscript() {
        ByteArrayOutputStream bar = new ByteArrayOutputStream();
        AtomicLong clock = new AtomicLong();
        Log log = new Log(out, new PrintStream(bar, true, StandardCharsets.UTF_8), Log.Level.NORMAL,
                false, true, clock::get);

        log.step("upload", "3 blobs");
        clock.addAndGet(400_000_000L);
        log.progress("upload", 1, 3, 1024, "blob");
        clock.addAndGet(600_000_000L);
        log.progress("upload", 2, 3, 2048, "blob");

        assertTrue(bar.toString().contains("2/3 blobs"), bar.toString());
        assertFalse(printed.toString().contains("2/3"), printed.toString());
        assertFalse(log.transcript().stream().anyMatch(line -> line.contains("2/3")),
                log.transcript().toString());
    }

    @Test
    void keepsTheTranscriptPlainWhileTheTerminalGetsColour() {
        Log log = new Log(out, Log.Level.VERBOSE, true);

        log.item("replace", "config/x.cfg");

        assertTrue(printed.toString().contains("\u001b["), "the terminal was coloured");
        assertEquals("    replace  config/x.cfg", log.transcript().get(0));
    }

    @Test
    void opensAStageWithAHeadingAndClosesWithASummary() {
        Log log = new Log(out, Log.Level.NORMAL, true);
        log.headline("publish client 1.0.0");
        log.phase("transfer");
        log.step("upload", "2 blobs");
        log.done("client 1.0.0 is now the latest");
        log.summary("uploaded", "2 blobs, 7 B in 0.4s (17.5 B/s)");

        String[] lines = printed.toString().split(System.lineSeparator());
        assertEquals("publish client 1.0.0", lines[0]);
        assertEquals("", lines[1], "a stage is set off by a blank line");
        assertEquals("\u001b[1mtransfer\u001b[0m", lines[2]);
        assertEquals("  \u001b[36mupload   \u001b[0m2 blobs", lines[3]);
        assertTrue(lines[4].startsWith("\u001b[1mdone\u001b[0m: client 1.0.0 is now the latest, "), lines[4]);
        assertEquals("  \u001b[1muploaded  \u001b[0m2 blobs, 7 B in 0.4s (17.5 B/s)", lines[5]);

        List<String> transcript = log.transcript();
        assertEquals("", transcript.get(1));
        assertEquals("transfer", transcript.get(2), "the transcript has no colour");
        assertTrue(transcript.contains("  uploaded  2 blobs, 7 B in 0.4s (17.5 B/s)"), transcript.toString());
    }

    @Test
    void showsDurationsAndRatesTheWayTheSummariesWant() {
        assertEquals("0.4s", Log.duration(400_000_000L));
        assertEquals("12.0s", Log.duration(12_000_000_000L));
        assertEquals("4 B/s", Sizes.rate(4, 1_000_000_000L));
        assertEquals("30.1 MiB/s", Sizes.rate(31_562_137L, 1_000_000_000L));
        assertEquals("7 B/s", Sizes.rate(7, 0), "a transfer too fast to time is reported as its size");
    }
}

package io.github.deshark42.modpackupdater.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
        assertTrue(lines[1].startsWith("  plan    12 added, 3 replaced, 1 deleted"), lines[1]);
        assertEquals(50, lines[1].indexOf("48.2 MiB to download"), lines[1]);
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
                "    add     mods/a.jar",
                "    replace config/x.cfg",
                ""), printed.toString());
    }

    @Test
    void quietKeepsOnlyTheClosingLine() {
        Log log = new Log(out, Log.Level.QUIET);
        log.headline("update client");
        log.step("plan", "1 added", "");
        log.note("nothing to see");
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
    void progressIsDueAtMostOnceASecond() {
        AtomicLong clock = new AtomicLong();
        Log log = new Log(out, Log.Level.NORMAL, false, clock::get);

        assertFalse(log.progressDue());
        clock.addAndGet(999_999_999L);
        assertFalse(log.progressDue());
        clock.addAndGet(1L);
        assertTrue(log.progressDue());
        assertFalse(log.progressDue());
        clock.addAndGet(2_000_000_000L);
        assertTrue(log.progressDue());
    }

    @Test
    void coloursFileVerbsButNotOtherLines() {
        Log log = new Log(out, Log.Level.VERBOSE, true);
        log.item("add", "mods/a.jar");
        log.item("delete", "mods/b.jar");
        log.step("scan", "client/base");
        log.done("client is now at 2.9.1");

        String text = printed.toString();
        assertTrue(text.contains("\u001b[32madd     \u001b[0mmods/a.jar"), text);
        assertTrue(text.contains("\u001b[31mdelete  \u001b[0mmods/b.jar"), text);
        assertTrue(text.contains("  scan    client/base"), text);
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
}

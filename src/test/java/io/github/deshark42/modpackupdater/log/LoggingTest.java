package io.github.deshark42.modpackupdater.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LoggingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);
    private static final String TRANSCRIPT = "update-client";

    /** A logger of this test's own, so the messages asserted on are the ones this test wrote. */
    private static Logger logger() {
        return LoggerFactory.getLogger(LoggingTest.class);
    }

    @Test
    void printsTheTimeTheLevelAndTheMessage(@TempDir Path workspace) {
        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace, Logging.Mode.NORMAL);
            logger().info("the run starts");

            assertTrue(logs.outText().lines().anyMatch(line ->
                    line.matches("\\d{2}:\\d{2}:\\d{2}\\.\\d{3} INFO the run starts")), logs.outText());
        }
    }

    @Test
    void eachModeShowsTheLevelsItIsMeantTo(@TempDir Path workspace) {
        assertEquals(List.of("INFO"), levels(workspace, Logging.Mode.QUIET));
        assertEquals(List.of("DEBUG", "INFO"), levels(workspace, Logging.Mode.NORMAL));
        assertEquals(List.of("TRACE", "DEBUG", "INFO"), levels(workspace, Logging.Mode.VERBOSE));
    }

    /** Logs one line per level and returns the levels that reached the console, in order. */
    private static List<String> levels(Path workspace, Logging.Mode mode) {
        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace, mode);
            Logger log = logger();
            log.trace("a file the run moved");
            log.debug("a fact the stage reports");
            log.info("a milestone");
            return logs.outText().lines()
                    .map(line -> line.split(" ", 3)[1])
                    .toList();
        }
    }

    @Test
    void warningsAndErrorsGoToTheErrorStream(@TempDir Path workspace) {
        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace, Logging.Mode.VERBOSE);
            Logger log = logger();
            log.info("a milestone");
            log.debug("a fact the stage reports");
            log.trace("a file the run moved");
            log.warn("a caution");
            log.error("a failure");

            assertTrue(logs.outText().contains("INFO a milestone"), logs.outText());
            assertTrue(logs.outText().contains("DEBUG a fact the stage reports"), logs.outText());
            assertTrue(logs.outText().contains("TRACE a file the run moved"), logs.outText());
            assertTrue(logs.errText().contains("WARN a caution"), logs.errText());
            assertTrue(logs.errText().contains("ERROR a failure"), logs.errText());
            assertFalse(logs.outText().contains("WARN "), logs.outText());
            assertFalse(logs.errText().contains("INFO "), logs.errText());
        }
    }

    @Test
    void aNamedRunKeepsItsTranscriptInTheLogsDirectory(@TempDir Path workspace) throws IOException {
        Path transcript =
                Layout.logsDir(workspace).resolve(TRANSCRIPT + "-" + LocalDate.now(CLOCK) + ".log");
        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace, TRANSCRIPT, Logging.Mode.QUIET, CLOCK);
            logger().info("the first entry");
            logger().trace("a file the run moved");

            assertTrue(logs.outText().contains("INFO the first entry"), logs.outText());
            assertFalse(logs.outText().contains("TRACE a file the run moved"),
                    "the quiet console keeps the file lines out: " + logs.outText());
        }

        assertTrue(Files.isRegularFile(transcript), transcript.toString());
        String text = Files.readString(transcript, StandardCharsets.UTF_8);
        assertTrue(text.lines().anyMatch(line ->
                line.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} INFO the first entry")), text);
        assertTrue(text.contains("TRACE a file the run moved"), text);

        try (TestLogs second = new TestLogs()) {
            second.begin(workspace, TRANSCRIPT, Logging.Mode.QUIET, CLOCK);
            logger().info("the second entry");
        }

        String appended = Files.readString(transcript, StandardCharsets.UTF_8);
        assertTrue(appended.contains("INFO the first entry"), appended);
        assertTrue(appended.contains("INFO the second entry"), appended);
        try (Stream<Path> files = Files.list(Layout.logsDir(workspace))) {
            assertEquals(1, files.filter(file -> file.getFileName().toString().endsWith(".log")).count(),
                    "a second run the same day appends to the same file");
        }
    }

    @Test
    void prunesTranscriptsOlderThanTheRetentionWindow(@TempDir Path workspace) throws IOException {
        Path logsDir = Layout.logsDir(workspace);
        Files.createDirectories(logsDir);
        Path old = logsDir.resolve("old-run.log");
        Path recent = logsDir.resolve("recent-run.log");
        Path other = logsDir.resolve("notes.txt");
        Files.writeString(old, "old", StandardCharsets.UTF_8);
        Files.writeString(recent, "recent", StandardCharsets.UTF_8);
        Files.writeString(other, "kept", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(old, FileTime.from(CLOCK.instant().minus(Duration.ofDays(15))));
        Files.setLastModifiedTime(recent, FileTime.from(CLOCK.instant().minus(Duration.ofDays(1))));
        Files.setLastModifiedTime(other, FileTime.from(CLOCK.instant().minus(Duration.ofDays(15))));

        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace, TRANSCRIPT, Logging.Mode.QUIET, CLOCK);
            logger().info("a milestone");
        }

        assertFalse(Files.exists(old), "a transcript older than the retention window is removed");
        assertTrue(Files.exists(recent), "a transcript inside the window stays");
        assertTrue(Files.exists(other), "only transcripts are pruned");
        assertTrue(Files.exists(logsDir.resolve(TRANSCRIPT + "-" + LocalDate.now(CLOCK) + ".log")),
                "the run still keeps its own transcript");
    }

    @Test
    void aRunWithoutATranscriptNameKeepsNoFile(@TempDir Path workspace) {
        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace, Logging.Mode.NORMAL);
            logger().info("a milestone");

            assertTrue(logs.outText().contains("INFO a milestone"), logs.outText());
        }

        assertFalse(Files.exists(Layout.logsDir(workspace)), "no transcript directory is created");
    }
}

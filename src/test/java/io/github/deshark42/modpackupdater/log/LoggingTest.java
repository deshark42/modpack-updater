package io.github.deshark42.modpackupdater.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.UserError;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ResourceLock(TestLogs.RESOURCE)
class LoggingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);
    private static final String TRANSCRIPT = "update-client";

    private static Logger logger() {
        return LoggerFactory.getLogger(LoggingTest.class);
    }

    private static List<String> messages(String text) {
        return text.lines().map(line -> line.replaceFirst("^.*? (TRACE|DEBUG|INFO|WARN|ERROR) ", "$1 ")).toList();
    }

    private static List<LocalDateTime> entryTimes(String text) {
        return text.lines().map(line -> {
            String[] fields = line.strip().split("\\s+", 3);
            return LocalDateTime.parse(fields[0] + "T" + fields[1]);
        }).toList();
    }

    @Test
    void aRunKeepsTheInjectedClockAndItsZone(@TempDir Path workspace) {
        Clock clock = Clock.fixed(CLOCK.instant(), ZoneOffset.ofHours(9));
        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace, null, clock);

            assertEquals(clock, Logging.current().clock());
            assertEquals(clock.instant(), Logging.current().clock().instant());
            assertEquals(clock.getZone(), Logging.current().clock().getZone());
        }
    }

    @ParameterizedTest(name = "{0} reaches stdout={1} stderr={2}")
    @CsvSource({"INFO,true,false", "WARN,false,true", "ERROR,false,true"})
    void aLogLevelSelectsItsConsoleStream(String level, boolean out, boolean err, @TempDir Path workspace) {
        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace, null, CLOCK);
            Logger log = logger();

            switch (level) {
                case "INFO" -> log.info("a message");
                case "WARN" -> log.warn("a message");
                case "ERROR" -> log.error("a message");
                default -> throw new AssertionError(level);
            }

            assertEquals(out ? List.of(level + " a message") : List.of(), messages(logs.outText()));
            assertEquals(err ? List.of(level + " a message") : List.of(), messages(logs.errText()));
        }
    }

    @Test
    void aRunKeepsDebugInItsTranscriptButNotOnTheConsole(@TempDir Path workspace) throws IOException {
        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace, TRANSCRIPT, CLOCK);

            logger().debug("a fact the run found");

            assertFalse(logs.outText().contains("a fact the run found"), logs.outText());
            assertEquals("", logs.errText());
        }
        assertEquals(List.of("INFO logging this run to " + Text.relativeTo(workspace, transcript(workspace)),
                "DEBUG a fact the run found"), messages(Files.readString(transcript(workspace), StandardCharsets.UTF_8)));
    }

    @Test
    void aRunDoesNotKeepTraceInEitherDestination(@TempDir Path workspace) throws IOException {
        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace, TRANSCRIPT, CLOCK);

            logger().trace("a file the run moved");

            assertEquals(List.of("INFO logging this run to " + Text.relativeTo(workspace, transcript(workspace))),
                    messages(logs.outText()));
            assertEquals("", logs.errText());
        }
        assertEquals(List.of("INFO logging this run to " + Text.relativeTo(workspace, transcript(workspace))),
                messages(Files.readString(transcript(workspace), StandardCharsets.UTF_8)));
    }

    @Test
    void capturedOutputDoesNotContainProgressFrames(@TempDir Path workspace) {
        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace, null, CLOCK);

            Logging.progress("check", 5, 10, "object");

            assertEquals("", logs.outText());
            assertEquals("", logs.errText());
        }
    }

    @Test
    void countedProgressReachesTheRunsTerminal(@TempDir Path workspace) {
        try (TestLogs logs = new TestLogs()) {
            AtomicLong nanos = new AtomicLong(1_000_000_000L);
            Progress progress = new Progress(logs.err(), true, nanos::get);
            Logging.begin(workspace, null, logs.out(), logs.err(), CLOCK, progress);
            Logging.progress("check", 0, 10, "object");
            nanos.addAndGet(1_000_000_000L);

            Logging.progress("check", 4, 10, "object");

            assertEquals("check", logs.errText().strip().split("\\s+")[0]);
            assertTrue(logs.errText().contains("4/10"), logs.errText());
            assertEquals("", logs.outText());
        }
    }

    @Test
    void aNamedRunCreatesItsTranscriptUnderTheLogsDirectory(@TempDir Path workspace) throws IOException {
        Clock clock = Clock.fixed(CLOCK.instant(), ZoneOffset.ofHours(9));
        LocalDateTime expectedTime = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace, TRANSCRIPT, clock);

            logger().info("the first entry");

            assertEquals(List.of("INFO logging this run to " + Text.relativeTo(workspace, transcript(workspace)),
                    "INFO the first entry"), messages(logs.outText()));
            assertEquals(List.of(expectedTime, expectedTime), entryTimes(logs.outText()));
        }
        String transcriptText = Files.readString(transcript(workspace), StandardCharsets.UTF_8);
        assertEquals(List.of("INFO logging this run to " + Text.relativeTo(workspace, transcript(workspace)),
                "INFO the first entry"), messages(transcriptText));
        assertEquals(List.of(expectedTime, expectedTime), entryTimes(transcriptText));
        try (var files = Files.list(Layout.logsDir(workspace))) {
            assertEquals(List.of(transcript(workspace)), files.toList());
        }
    }

    @Test
    void aSecondRunOnTheSameDayAppendsToTheSameTranscript(@TempDir Path workspace) throws IOException {
        try (TestLogs first = new TestLogs()) {
            first.begin(workspace, TRANSCRIPT, CLOCK);
            logger().info("the first entry");
        }
        String original = Files.readString(transcript(workspace), StandardCharsets.UTF_8);

        try (TestLogs second = new TestLogs()) {
            second.begin(workspace, TRANSCRIPT, CLOCK);
            logger().info("the second entry");
        }

        String appended = Files.readString(transcript(workspace), StandardCharsets.UTF_8);
        assertTrue(appended.startsWith(original), appended);
        assertEquals(List.of("INFO logging this run to " + Text.relativeTo(workspace, transcript(workspace)),
                "INFO the first entry", "INFO logging this run to " + Text.relativeTo(workspace, transcript(workspace)),
                "INFO the second entry"), messages(appended));
        try (var files = Files.list(Layout.logsDir(workspace))) {
            assertEquals(List.of(transcript(workspace)), files.toList());
        }
    }

    @ParameterizedTest(name = "{0} aged {1} hours remains={2}")
    @CsvSource({"outside.log,721,false", "boundary.log,720,true", "inside.log,719,true", "notes.txt,744,true"})
    void onlyTranscriptsOlderThanTheRetentionWindowArePruned(String name, long hours, boolean retained,
            @TempDir Path workspace) throws IOException {
        Path logsDir = Layout.logsDir(workspace);
        Files.createDirectories(logsDir);
        Path old = logsDir.resolve(name);
        Files.writeString(old, "retained content", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(old, FileTime.from(CLOCK.instant().minus(Duration.ofHours(hours))));

        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace, TRANSCRIPT, CLOCK);
        }

        assertEquals(retained, Files.exists(old));
        if (retained) {
            assertEquals("retained content", Files.readString(old, StandardCharsets.UTF_8));
        }
        assertTrue(Files.isRegularFile(transcript(workspace)));
    }

    @Test
    void aRunWithoutATranscriptNameCreatesNoFile(@TempDir Path workspace) {
        try (TestLogs logs = new TestLogs()) {
            logs.begin(workspace, null, CLOCK);

            logger().info("a milestone");

            assertEquals(List.of("INFO a milestone"), messages(logs.outText()));
        }
        assertFalse(Files.exists(Layout.logsDir(workspace)));
    }

    @Test
    void aLinkedTranscriptIsRejectedBeforeAnExternalFileIsOpened(@TempDir Path root) throws IOException {
        Path workspace = root.resolve("workspace");
        Files.createDirectories(Layout.logsDir(workspace));
        Path external = root.resolve("external.log");
        Files.writeString(external, "external", StandardCharsets.UTF_8);
        Path transcript = transcript(workspace);
        try {
            Files.createSymbolicLink(transcript, external);
        } catch (UnsupportedOperationException | IOException | SecurityException error) {
            assumeTrue(false, "symbolic links are unavailable on this platform: " + error);
        }
        try (TestLogs logs = new TestLogs()) {
            UserError error = assertThrows(UserError.class, () -> logs.begin(workspace, TRANSCRIPT, CLOCK));

            assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
            assertEquals("external", Files.readString(external, StandardCharsets.UTF_8));
            assertEquals("", logs.outText());
            assertEquals("", logs.errText());
            try (var files = Files.list(Layout.logsDir(workspace))) {
                assertEquals(List.of(transcript), files.toList());
            }
        }
    }

    private static Path transcript(Path workspace) {
        return Layout.logsDir(workspace).resolve(TRANSCRIPT + "-" + LocalDate.now(CLOCK) + ".log");
    }
}

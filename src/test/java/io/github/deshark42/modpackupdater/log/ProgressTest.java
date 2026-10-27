package io.github.deshark42.modpackupdater.log;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProgressTest {

    private static final class TestTerminal implements AutoCloseable {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final PrintStream err = new PrintStream(output, true, StandardCharsets.UTF_8);
        final AtomicLong nanos = new AtomicLong(1_000_000_000L);
        final Progress progress = new Progress(err, true, nanos::get);

        String text() {
            return output.toString(StandardCharsets.UTF_8);
        }

        String summary() {
            return text().replace("\r", "").replace("\u001b[K", "")
                    .replaceAll("[#-]{10,}", "").replace("·", "").strip().replaceAll("\\s+", " ");
        }

        @Override
        public void close() {
            err.close();
        }
    }

    @Test
    void countedWorkShowsObjectRateAndRemainingTime() {
        try (TestTerminal terminal = new TestTerminal()) {
            terminal.progress.update("check", 0, 10, -1, "object");
            terminal.nanos.addAndGet(1_000_000_000L);

            terminal.progress.update("check", 4, 10, -1, "object");

            assertEquals("check 40% 4/10 objects 4 objects/s ETA 0:02", terminal.summary());
        }
    }

    @Test
    void aNewVerbStartsANewRateAndTimer() {
        try (TestTerminal terminal = new TestTerminal()) {
            terminal.progress.update("check", 0, 10, -1, "object");
            terminal.nanos.addAndGet(1_000_000_000L);
            terminal.progress.update("check", 4, 10, -1, "object");
            terminal.progress.clear();
            terminal.progress.update("upload", 0, 10, 0, "chunk");
            terminal.output.reset();
            terminal.nanos.addAndGet(1_000_000_000L);

            terminal.progress.update("upload", 2, 10, 2048, "chunk");

            assertEquals("upload 20% 2/10 chunks 2.0 KiB/s ETA 0:04", terminal.summary());
        }
    }

    @Test
    void aResetStartsANewRateForTheSameVerb() {
        try (TestTerminal terminal = new TestTerminal()) {
            terminal.progress.update("download", 100, 1000, 1_048_576, "chunk");
            terminal.nanos.addAndGet(1_000_000_000L);
            terminal.progress.update("download", 200, 1000, 2_097_152, "chunk");
            terminal.progress.reset();
            terminal.progress.update("download", 1, 10, 1024, "chunk");
            terminal.output.reset();
            terminal.nanos.addAndGet(1_000_000_000L);

            terminal.progress.update("download", 3, 10, 3072, "chunk");

            assertEquals("download 30% 3/10 chunks 2.0 KiB/s ETA 0:04", terminal.summary());
        }
    }

    @Test
    void clearingPreservesTheCurrentTransfersRateAndTimer() {
        try (TestTerminal terminal = new TestTerminal()) {
            terminal.progress.update("download", 10, 100, 10_240, "chunk");
            terminal.nanos.addAndGet(1_000_000_000L);
            terminal.progress.update("download", 20, 100, 20_480, "chunk");
            terminal.progress.clear();
            terminal.output.reset();
            terminal.nanos.addAndGet(100_000_000L);

            terminal.progress.update("download", 21, 100, 21_504, "chunk");

            assertEquals("download 21% 21/100 chunks 10.0 KiB/s ETA 0:08", terminal.summary());
        }
    }

    @ParameterizedTest(name = "done {0} of {1} after {2}ns leaves no frame")
    @CsvSource({"0,0,1000000000", "1,2,100000000", "2,2,1000000000"})
    void emptyQuickOrCompletedWorkDoesNotDrawAFrame(long done, long total, long elapsed) {
        try (TestTerminal terminal = new TestTerminal()) {
            terminal.progress.update("check", 0, total, -1, "object");
            terminal.nanos.addAndGet(elapsed);

            terminal.progress.update("check", done, total, -1, "object");

            assertEquals("", terminal.text());
        }
    }

    @Test
    void aResetRestartsTheSettleWindow() {
        try (TestTerminal terminal = new TestTerminal()) {
            terminal.progress.update("check", 0, 10, -1, "object");
            terminal.nanos.addAndGet(1_000_000_000L);
            terminal.progress.update("check", 4, 10, -1, "object");
            terminal.progress.reset();
            terminal.progress.update("check", 0, 10, -1, "object");
            terminal.output.reset();
            terminal.nanos.addAndGet(200_000_000L);

            terminal.progress.update("check", 1, 10, -1, "object");

            assertEquals("", terminal.text());
        }
    }

    @Test
    void clearingAnAlreadyClearedBarWritesNothing() {
        try (TestTerminal terminal = new TestTerminal()) {
            terminal.progress.update("check", 0, 10, -1, "object");
            terminal.nanos.addAndGet(1_000_000_000L);
            terminal.progress.update("check", 4, 10, -1, "object");
            terminal.progress.clear();
            terminal.output.reset();

            terminal.progress.clear();

            assertEquals("", terminal.text());
        }
    }
}

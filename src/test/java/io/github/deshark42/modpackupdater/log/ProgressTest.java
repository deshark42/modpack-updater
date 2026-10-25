package io.github.deshark42.modpackupdater.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ProgressTest {

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final PrintStream err = new PrintStream(output, true, StandardCharsets.UTF_8);
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final Progress progress = new Progress(err, true, nanos::get);

    private String text() {
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void countedWorkShowsObjectRateAndRemainingTime() {
        progress.update("check", 0, 10, -1, "object");
        nanos.addAndGet(1_000_000_000L);

        progress.update("check", 4, 10, -1, "object");

        assertTrue(text().contains("check"), text());
        assertTrue(text().contains("40% · 4/10 objects · 4 objects/s · ETA 0:02"), text());
        assertFalse(text().contains("B/s"), text());
    }

    @Test
    void transferringAfterCheckingStartsANewRateAndTimer() {
        progress.update("check", 0, 10, -1, "object");
        nanos.addAndGet(1_000_000_000L);
        progress.update("check", 4, 10, -1, "object");
        progress.clear();
        output.reset();

        progress.update("upload", 0, 10, 0, "chunk");
        assertEquals("", text());
        nanos.addAndGet(1_000_000_000L);
        progress.update("upload", 2, 10, 2048, "chunk");

        assertTrue(text().contains("upload"), text());
        assertTrue(text().contains("20% · 2/10 chunks · 2.0 KiB/s · ETA 0:04"), text());
        assertFalse(text().contains("objects/s"), text());
    }

    @Test
    void resettingRestartsTheSettleWindowAndRateForTheSameVerb() {
        progress.update("download", 100, 1000, 1_048_576, "chunk");
        nanos.addAndGet(1_000_000_000L);
        progress.update("download", 200, 1000, 2_097_152, "chunk");
        output.reset();

        progress.reset();

        assertEquals("\r\u001b[K", text());
        output.reset();
        progress.update("download", 1, 10, 1024, "chunk");
        assertEquals("", text());
        nanos.addAndGet(200_000_000L);
        progress.update("download", 2, 10, 2048, "chunk");
        assertEquals("", text(), "the new transfer gets its own settle window");
        nanos.addAndGet(800_000_000L);
        progress.update("download", 3, 10, 3072, "chunk");

        assertTrue(text().contains("30% · 3/10 chunks · 2.0 KiB/s · ETA 0:04"), text());
        assertFalse(text().contains("· -"), "the earlier transfer cannot produce a negative rate: " + text());
    }

    @Test
    void clearingPreservesTheCurrentTransfersRateAndTimer() {
        progress.update("download", 10, 100, 10_240, "chunk");
        nanos.addAndGet(1_000_000_000L);
        progress.update("download", 20, 100, 20_480, "chunk");

        progress.clear();

        output.reset();
        nanos.addAndGet(100_000_000L);
        progress.update("download", 21, 100, 21_504, "chunk");

        assertTrue(text().contains("21% · 21/100 chunks · 10.0 KiB/s · ETA 0:08"), text());
    }

    @Test
    void quickChecksAndEmptyWorkDoNotDrawAFrame() {
        progress.update("check", 0, 0, -1, "object");
        progress.update("check", 0, 2, -1, "object");
        nanos.addAndGet(100_000_000L);
        progress.update("check", 1, 2, -1, "object");
        progress.update("check", 2, 2, -1, "object");
        progress.clear();

        assertEquals("", text());
    }

    @Test
    void clearingRemovesTheCheckBarOnlyOnce() {
        progress.update("check", 0, 10, -1, "object");
        nanos.addAndGet(1_000_000_000L);
        progress.update("check", 4, 10, -1, "object");
        output.reset();

        progress.clear();
        progress.clear();

        assertEquals("\r\u001b[K", text());
    }
}

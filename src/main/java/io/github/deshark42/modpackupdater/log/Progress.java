package io.github.deshark42.modpackupdater.log;

import io.github.deshark42.modpackupdater.fs.Sizes;
import java.io.PrintStream;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Work in flight as one line redrawn in place: how far along, how fast, and how much longer. Only
 * ever drawn on a terminal that can take one; everything else gets no frames at all, so a run piped
 * into a file leaves no half-drawn line behind.
 */
final class Progress {

    /** A bar redrawn in place is worth a few frames a second, no more. */
    private static final long FRAME = 100_000_000L;
    /** A transfer that is nearly over before it starts never gets a bar at all. */
    private static final long SETTLE = 250_000_000L;
    private static final int MIN_BAR = 10;
    private static final int MAX_BAR = 30;
    private static final int FALLBACK_WIDTH = 80;
    private static final int VERB_WIDTH = 9;
    private static final String FILLED = "#";
    private static final String EMPTY = "-";

    private final PrintStream err;
    private final boolean enabled;
    private final LongSupplier nanoTime;
    private boolean drawn;
    private long startedAt;
    private long lastFrame;
    private long startDone;
    private long startBytes;
    private String verb = "";

    Progress(PrintStream err, boolean enabled) {
        this(err, enabled, System::nanoTime);
    }

    Progress(PrintStream err, boolean enabled, LongSupplier nanoTime) {
        this.err = err;
        this.enabled = enabled;
        this.nanoTime = nanoTime;
    }

    /** Work finished; negative bytes select an object rate instead of a transfer rate. */
    synchronized void update(String verb, long done, long total, long bytes, String noun) {
        if (!enabled || total <= 0 || done >= total) {
            return;
        }
        long now = nanoTime.getAsLong();
        if (!verb.equals(this.verb)) {
            this.verb = verb;
            startedAt = now;
            lastFrame = 0;
            startDone = done;
            startBytes = bytes;
        }
        if (now - startedAt < SETTLE || now - lastFrame < FRAME) {
            return;
        }
        lastFrame = now;
        long nanos = Math.max(1, now - startedAt);
        double seconds = nanos / 1e9;
        long perSecond = seconds > 0 ? Math.round((done - startDone) / seconds) : 0;
        String moved = bytes < 0 ? count(perSecond, noun) + "/s" : Sizes.rate(bytes - startBytes, nanos);
        String tail = String.format(Locale.ROOT, "%3d%% \u00b7 %d/%s \u00b7 %s \u00b7 ETA %s",
                100 * done / total, done, count(total, noun), moved, eta(total - done, perSecond));
        int width = barWidth(tail.length());
        int filled = (int) (width * done / total);
        String text = "  " + verb + " ".repeat(Math.max(1, VERB_WIDTH - verb.length()))
                + FILLED.repeat(filled) + EMPTY.repeat(width - filled) + " " + tail;
        err.print("\r" + text + "\u001b[K");
        err.flush();
        drawn = true;
    }

    /** Starts a separate operation, even when it reports the same verb as the previous one. */
    synchronized void reset() {
        clear();
        verb = null;
    }

    /** Erases the bar, if one is on screen, so whatever is printed next starts on a clean line. */
    synchronized void clear() {
        if (drawn) {
            err.print("\r\u001b[K");
            err.flush();
            drawn = false;
        }
    }

    /** "0:42", or "1:02:03" once the wait is long enough to need the hours. */
    private static String eta(long remaining, long perSecond) {
        if (perSecond <= 0 || remaining <= 0) {
            return "--:--";
        }
        long seconds = Math.round((double) remaining / perSecond);
        return seconds >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds % 3600 / 60,
                        seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** How wide the bar can be without pushing the tail off the end of the terminal. */
    private static int barWidth(int tail) {
        int columns = FALLBACK_WIDTH;
        String configured = System.getenv("COLUMNS");
        if (configured != null) {
            try {
                columns = Integer.parseInt(configured.trim());
            } catch (NumberFormatException e) {
                columns = FALLBACK_WIDTH;
            }
        }
        return Math.max(MIN_BAR, Math.min(MAX_BAR, columns - 2 - VERB_WIDTH - tail - 1));
    }

    private static String count(long number, String noun) {
        return number + " " + noun + (number == 1 ? "" : "s");
    }
}

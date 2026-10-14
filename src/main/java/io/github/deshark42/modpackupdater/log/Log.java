package io.github.deshark42.modpackupdater.log;

import io.github.deshark42.modpackupdater.fs.Sizes;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * The one place that decides what a command's output looks like: a headline saying what is about to
 * happen, indented steps, one item per file only when asked for, and a closing line saying how it
 * went. Commands never print anything themselves.
 *
 * <p>Every line is built as {@code indent + verb padded to 9 + subject + note}: the verb column can
 * be read down a log, and a note is pushed out past the longest subject it has to line up with.
 *
 * <p>A run is read in stages: {@link #phase} opens one with a heading of its own, and the closing
 * line is followed by a {@link #summary} of what the run did, so the last few lines say what
 * happened without reading back up.
 *
 * <p>Everything that records what the run did is also kept as plain text in a transcript, whatever
 * the console was asked to show, so a run that moved data can leave the detail behind in a file.
 * Work in flight is the one thing that is not recorded: it is a bar redrawn in place on a terminal
 * that can take one, and nothing at all anywhere else.
 */
public final class Log {

    /** How much of the log to print. */
    public enum Level {
        /** Only the closing line. */
        QUIET,
        /** Headline, stage headings, steps, the closing line and the summary. */
        NORMAL,
        /** Everything, including one item per file. */
        VERBOSE
    }

    /** Wide enough for the longest verb, so every subject starts in the same column. */
    private static final int VERB_WIDTH = 9;
    private static final int SUBJECT_WIDTH = 40;
    /** Wide enough for the longest summary key. */
    private static final int KEY_WIDTH = 10;
    /** A bar redrawn in place is worth a few frames a second, no more. */
    private static final long FRAME = 100_000_000L;
    /** A transfer that is nearly over before it starts never gets a bar at all. */
    private static final long SETTLE = 250_000_000L;
    /** Rates and times left come from the last few seconds of samples, not from the whole run. */
    private static final long WINDOW = 15_000_000_000L;
    private static final int MIN_BAR = 10;
    private static final int MAX_BAR = 30;
    private static final int FALLBACK_WIDTH = 80;
    private static final String FILLED = "#";
    private static final String EMPTY = "-";

    private static final String RESET = "\u001b[0m";
    private static final String BOLD = "\u001b[1m";
    private static final String GREEN = "\u001b[32m";
    private static final String YELLOW = "\u001b[33m";
    private static final String RED = "\u001b[31m";
    private static final String CYAN = "\u001b[36m";

    private final PrintStream out;
    private final PrintStream progressOut;
    private final Level level;
    private final boolean color;
    private final boolean bar;
    private final LongSupplier clock;
    private final long startedAt;
    private final List<String> transcript = Collections.synchronizedList(new ArrayList<>());
    private final Deque<long[]> samples = new ArrayDeque<>();
    private boolean barDrawn;
    private long progressStartedAt;
    private long lastFrame;
    private String progressVerb = "";

    public Log(PrintStream out) {
        this(out, Level.NORMAL, false);
    }

    public Log(PrintStream out, Level level) {
        this(out, level, false);
    }

    public Log(PrintStream out, Level level, boolean color) {
        this(out, level, color, System::nanoTime);
    }

    public Log(PrintStream out, Level level, boolean color, LongSupplier clock) {
        this(out, out, level, color, false, clock);
    }

    /**
     * @param progressOut where work in flight is drawn: a stream of its own, so redirecting the log
     *                    into a file leaves the bar where a human can still see it
     * @param bar         draw work in flight as one bar redrawn in place, rather than as lines
     */
    public Log(PrintStream out, PrintStream progressOut, Level level, boolean color, boolean bar,
            LongSupplier clock) {
        this.out = out;
        this.progressOut = progressOut;
        this.level = level;
        this.color = color;
        this.bar = bar;
        this.clock = clock;
        this.startedAt = clock.getAsLong();
    }

    /** Paths in the log are relative to the workspace the command was pointed at. */
    public static String relativeTo(Path workspace, Path path) {
        try {
            return workspace.relativize(path).toString();
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }

    /** "1 file", "3 files". */
    public static String count(int number, String noun) {
        return number + " " + noun + (number == 1 ? "" : "s");
    }

    public Level level() {
        return level;
    }

    /** Every line of this run as plain text, including the ones the console was not shown. */
    public List<String> transcript() {
        synchronized (transcript) {
            return List.copyOf(transcript);
        }
    }

    /** What the command is about to do, e.g. "publish client 2.9.0". */
    public synchronized void headline(String text) {
        transcript.add(text);
        if (level != Level.QUIET) {
            clearProgress();
            out.println(text);
        }
    }

    /** Opens a stage of the run, e.g. "transfer": a bold heading set off by a blank line. */
    public synchronized void phase(String label) {
        transcript.add("");
        transcript.add(label);
        if (level != Level.QUIET) {
            clearProgress();
            out.println();
            out.println(paint(label, BOLD));
        }
    }

    /** One line of the closing summary, e.g. "uploaded 2 blobs, 7 B in 0.4s (4 B/s)". */
    public synchronized void summary(String key, String value) {
        String column = key + " ".repeat(Math.max(1, KEY_WIDTH - key.length()));
        transcript.add("  " + column + value);
        if (level != Level.QUIET) {
            clearProgress();
            out.println("  " + paint(column, BOLD) + value);
        }
    }

    /** One fact about the run, e.g. "scan client/base 128 files, 1.2 GiB". */
    public void step(String verb, String subject) {
        step(verb, subject, "");
    }

    public synchronized void step(String verb, String subject, String note) {
        transcript.add(line(1, verb, subject, note, false));
        if (level != Level.QUIET) {
            clearProgress();
            out.println(line(1, verb, subject, note, true));
        }
    }

    /** One file the run touches; printed only at {@link Level#VERBOSE}. */
    public synchronized void item(String verb, String path) {
        transcript.add(line(2, verb, path, "", false));
        if (level == Level.VERBOSE) {
            clearProgress();
            out.println(line(2, verb, path, "", true));
        }
    }

    /**
     * Work in flight, e.g. "upload 112/240 blobs": one bar redrawn in place on a terminal, and
     * nothing anywhere else, so a run piped into a file leaves no half-drawn line behind. Called
     * once per file finished, which is what makes a bar that stopped moving mean something.
     */
    public synchronized void progress(String verb, long done, long total, long bytes, String noun) {
        if (level == Level.QUIET || !bar || total <= 0 || done >= total) {
            return;
        }
        long now = clock.getAsLong();
        if (!verb.equals(progressVerb)) {
            progressVerb = verb;
            progressStartedAt = now;
            samples.clear();
        }
        samples.addLast(new long[] {now, done, bytes});
        while (samples.size() > 2 && now - samples.peekFirst()[0] > WINDOW) {
            samples.removeFirst();
        }
        if (now - progressStartedAt < SETTLE || now - lastFrame < FRAME) {
            return;
        }
        lastFrame = now;
        drawBar(verb, done, total, bytes, noun, now);
    }

    /** Erases the bar, if one is on screen, so whatever is printed next starts on a clean line. */
    public synchronized void clearProgress() {
        if (barDrawn) {
            progressOut.print("\r\u001b[K");
            progressOut.flush();
            barDrawn = false;
        }
    }

    /** One frame: how far along, how fast, and how much longer, all on the line the bar owns. */
    private void drawBar(String verb, long done, long total, long bytes, String noun, long now) {
        long[] first = samples.peekFirst();
        double seconds = (now - first[0]) / 1e9;
        long perSecond = seconds > 0 ? Math.round((done - first[1]) / seconds) : 0;
        String moved = Sizes.rate(seconds > 0 ? (long) ((bytes - first[2]) / seconds)
                : bytes * 1_000_000_000L / Math.max(1, now - progressStartedAt), 1_000_000_000L);
        String tail = String.format(Locale.ROOT, "%3d%% \u00b7 %d/%s \u00b7 %s \u00b7 ETA %s",
                100 * done / total, done, count((int) total, noun), moved,
                eta(total - done, perSecond));
        int width = barWidth(tail.length());
        int filled = (int) (width * done / total);
        String text = "  " + verbColumn(verb)
                + FILLED.repeat(filled)
                + EMPTY.repeat(width - filled)
                + " " + tail;
        progressOut.print("\r" + text + "\u001b[K");
        progressOut.flush();
        barDrawn = true;
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

    /** The closing line of a run that did something. */
    public synchronized void done(String text) {
        String took = elapsed();
        transcript.add("done: " + text + ", " + took);
        clearProgress();
        out.println(paint("done", BOLD) + ": " + text + ", " + took);
    }

    /** The closing line of a dry run: the same shape, but nothing was written. */
    public synchronized void dryRun() {
        String closing = "dry run: nothing was written, " + elapsed();
        transcript.add(closing);
        clearProgress();
        out.println(closing);
    }

    private String line(int indent, String verb, String subject, String note, boolean painted) {
        StringBuilder text = new StringBuilder("  ".repeat(indent));
        String column = verbColumn(verb);
        text.append(painted ? paint(column, colorOf(verb)) : column).append(subject);
        if (!note.isEmpty()) {
            text.append(" ".repeat(Math.max(2, SUBJECT_WIDTH - subject.length()))).append(note);
        }
        return text.toString();
    }

    /** "0.4s": how long something took, said the way the closing lines say it. */
    public static String duration(long nanos) {
        return String.format(Locale.ROOT, "%.1fs", nanos / 1e9);
    }

    private String elapsed() {
        return duration(clock.getAsLong() - startedAt);
    }

    private String paint(String text, String code) {
        return color && code != null ? code + text + RESET : text;
    }

    /** A verb and the blanks that carry the subject out to the subject column. */
    private static String verbColumn(String verb) {
        return verb + " ".repeat(Math.max(1, VERB_WIDTH - verb.length()));
    }

    private static String colorOf(String verb) {
        return switch (verb) {
            case "add" -> GREEN;
            case "replace" -> YELLOW;
            case "delete" -> RED;
            case "download", "upload" -> CYAN;
            default -> null;
        };
    }
}

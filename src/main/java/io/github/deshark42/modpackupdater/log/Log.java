package io.github.deshark42.modpackupdater.log;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
 * The progress lines that only report work in flight stay on the console.
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
    private static final long ONE_SECOND = 1_000_000_000L;

    private static final String RESET = "\u001b[0m";
    private static final String BOLD = "\u001b[1m";
    private static final String GREEN = "\u001b[32m";
    private static final String YELLOW = "\u001b[33m";
    private static final String RED = "\u001b[31m";
    private static final String CYAN = "\u001b[36m";

    private final PrintStream out;
    private final Level level;
    private final boolean color;
    private final LongSupplier clock;
    private final long startedAt;
    private final List<String> transcript = Collections.synchronizedList(new ArrayList<>());
    private long lastProgress;

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
        this.out = out;
        this.level = level;
        this.color = color;
        this.clock = clock;
        this.startedAt = clock.getAsLong();
        this.lastProgress = this.startedAt;
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
    public void headline(String text) {
        transcript.add(text);
        if (level != Level.QUIET) {
            out.println(text);
        }
    }

    /** Opens a stage of the run, e.g. "transfer": a bold heading set off by a blank line. */
    public void phase(String label) {
        transcript.add("");
        transcript.add(label);
        if (level != Level.QUIET) {
            out.println();
            out.println(paint(label, BOLD));
        }
    }

    /** One line of the closing summary, e.g. "uploaded 2 blobs, 7 B in 0.4s (4 B/s)". */
    public void summary(String key, String value) {
        String column = key + " ".repeat(Math.max(1, KEY_WIDTH - key.length()));
        transcript.add("  " + column + value);
        if (level != Level.QUIET) {
            out.println("  " + paint(column, BOLD) + value);
        }
    }

    /** One fact about the run, e.g. "scan client/base 128 files, 1.2 GiB". */
    public void step(String verb, String subject) {
        step(verb, subject, "");
    }

    public void step(String verb, String subject, String note) {
        transcript.add(line(1, verb, subject, note, false));
        if (level != Level.QUIET) {
            out.println(line(1, verb, subject, note, true));
        }
    }

    /** One file the run touches; printed only at {@link Level#VERBOSE}. */
    public void item(String verb, String path) {
        transcript.add(line(2, verb, path, "", false));
        if (level == Level.VERBOSE) {
            out.println(line(2, verb, path, "", true));
        }
    }

    /** Work in flight, e.g. "upload 12/40 blobs": the console and nothing else. */
    public void progress(String verb, String subject, String note) {
        if (level != Level.QUIET) {
            out.println(line(1, verb, subject, note, true));
        }
    }

    /** True at most once a second, so a long loop can report progress without flooding the log. */
    public synchronized boolean progressDue() {
        long now = clock.getAsLong();
        if (now - lastProgress < ONE_SECOND) {
            return false;
        }
        lastProgress = now;
        return true;
    }

    /** The closing line of a run that did something. */
    public void done(String text) {
        String took = elapsed();
        transcript.add("done: " + text + ", " + took);
        out.println(paint("done", BOLD) + ": " + text + ", " + took);
    }

    /** The closing line of a dry run: the same shape, but nothing was written. */
    public void dryRun() {
        String closing = "dry run: nothing was written, " + elapsed();
        transcript.add(closing);
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

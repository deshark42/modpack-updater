package io.github.deshark42.modpackupdater.log;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * The one place that decides what a command's output looks like: a headline saying what is about to
 * happen, indented steps, one item per file only when asked for, and a closing line saying how it
 * went. Commands never print anything themselves.
 *
 * <p>Every line is built as {@code indent + verb padded to 8 + subject + note}: the verb column can
 * be read down a log, and a note is pushed out past the longest subject it has to line up with.
 */
public final class Log {

    /** How much of the log to print. */
    public enum Level {
        /** Only the closing line. */
        QUIET,
        /** Headline, steps and the closing line. */
        NORMAL,
        /** Everything, including one item per file. */
        VERBOSE
    }

    private static final int VERB_WIDTH = 8;
    private static final int SUBJECT_WIDTH = 40;
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

    /** What the command is about to do, e.g. "publish client 2.9.0". */
    public void headline(String text) {
        if (level != Level.QUIET) {
            out.println(text);
        }
    }

    /** One fact about the run, e.g. "scan client/base 128 files, 1.2 GiB". */
    public void step(String verb, String subject) {
        step(verb, subject, "");
    }

    public void step(String verb, String subject, String note) {
        if (level != Level.QUIET) {
            out.println(line(1, verb, subject, note));
        }
    }

    /** One file the run touches; printed only at {@link Level#VERBOSE}. */
    public void item(String verb, String path) {
        if (level == Level.VERBOSE) {
            out.println(line(2, verb, path, ""));
        }
    }

    /** An explanation rather than an action, e.g. what is still to be filled in. */
    public void note(String text) {
        step("note", text);
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
        out.println(paint("done", BOLD) + ": " + text + ", " + elapsed());
    }

    /** The closing line of a dry run: the same shape, but nothing was written. */
    public void dryRun() {
        out.println("dry run: nothing was written, " + elapsed());
    }

    private String line(int indent, String verb, String subject, String note) {
        StringBuilder text = new StringBuilder("  ".repeat(indent));
        text.append(paint(verbColumn(verb), colorOf(verb))).append(subject);
        if (!note.isEmpty()) {
            text.append(" ".repeat(Math.max(2, SUBJECT_WIDTH - subject.length()))).append(note);
        }
        return text.toString();
    }

    private String elapsed() {
        return String.format(Locale.ROOT, "%.1fs", (clock.getAsLong() - startedAt) / 1e9);
    }

    private String paint(String text, String code) {
        return color && code != null ? code + text + RESET : text;
    }

    /** A verb and the blanks that carry the subject out to the subject column. */
    private static String verbColumn(String verb) {
        return verb.length() >= VERB_WIDTH ? verb + " " : verb + " ".repeat(VERB_WIDTH - verb.length());
    }

    private static String colorOf(String verb) {
        return switch (verb) {
            case "add" -> GREEN;
            case "replace" -> YELLOW;
            case "delete" -> RED;
            case "download" -> CYAN;
            default -> null;
        };
    }
}

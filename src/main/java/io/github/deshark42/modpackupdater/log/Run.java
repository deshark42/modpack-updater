package io.github.deshark42.modpackupdater.log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import org.tinylog.Level;

/** What the run being logged is writing to; the writers read this on every entry. */
final class Run {

    /** The transcript a run leaves behind: the file it appends to, and the writer doing it. */
    record Transcript(Path file, BufferedWriter writer) {
    }

    private final Path workspace;
    private final Logging.Mode mode;
    private final PrintStream out;
    private final PrintStream err;
    private final Progress progress;
    private final String transcriptName;
    private Transcript transcript;

    Run(Path workspace, Logging.Mode mode, PrintStream out, PrintStream err, Progress progress,
            String transcriptName, Transcript transcript) {
        this.workspace = workspace;
        this.mode = mode;
        this.out = out;
        this.err = err;
        this.progress = progress;
        this.transcriptName = transcriptName;
        this.transcript = transcript;
    }

    Path workspace() {
        return workspace;
    }

    PrintStream out() {
        return out;
    }

    PrintStream err() {
        return err;
    }

    Progress progress() {
        return progress;
    }

    String transcriptName() {
        return transcriptName;
    }

    Path transcript() {
        return transcript == null ? null : transcript.file();
    }

    /** Whether the console was asked to show an entry at this level. */
    boolean consoleShows(Level level) {
        return switch (mode) {
            case QUIET -> level.ordinal() >= Level.INFO.ordinal();
            case NORMAL -> level.ordinal() >= Level.DEBUG.ordinal();
            case VERBOSE -> true;
        };
    }

    /** Appends one line to the transcript; a transcript that cannot be written is dropped. */
    synchronized void writeTranscript(String text) {
        if (transcript == null) {
            return;
        }
        try {
            transcript.writer().write(text);
            transcript.writer().newLine();
            transcript.writer().flush();
        } catch (IOException e) {
            transcript = null;
        }
    }

    /** Closes the transcript, if there is one left. */
    synchronized void closeTranscript() {
        if (transcript == null) {
            return;
        }
        try {
            transcript.writer().close();
        } catch (IOException e) {
            // a transcript that cannot be closed is still readable
        }
        transcript = null;
    }
}

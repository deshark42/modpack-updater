package io.github.deshark42.modpackupdater.log;

import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import org.tinylog.Level;
import org.tinylog.core.LogEntry;
import org.tinylog.core.LogEntryValue;
import org.tinylog.writers.Writer;

/**
 * Writes one log line to the console: the time, the level, and the message. Warnings and errors go
 * to the error stream, everything else to the output stream.
 */
public final class ConsoleWriter implements Writer {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public ConsoleWriter(Map<String, String> properties) {
        // configured by Logging; a run's streams live in Run, because they change between runs
    }

    @Override
    public Collection<LogEntryValue> getRequiredLogEntryValues() {
        return EnumSet.of(LogEntryValue.DATE, LogEntryValue.LEVEL, LogEntryValue.MESSAGE,
                LogEntryValue.EXCEPTION);
    }

    @Override
    public void write(LogEntry entry) {
        Run run = Logging.current();
        if (run == null || !run.consoleShows(entry.getLevel())) {
            return;
        }
        run.progress().clear();
        PrintStream sink = entry.getLevel().ordinal() >= Level.WARN.ordinal() ? run.err() : run.out();
        String message = entry.getMessage() == null ? "" : entry.getMessage();
        sink.println(STAMP.format(entry.getTimestamp().toInstant().atZone(ZoneId.systemDefault()))
                + " " + entry.getLevel() + " " + message);
        Throwable error = entry.getException();
        if (error != null) {
            error.printStackTrace(sink);
        }
        sink.flush();
    }

    @Override
    public void flush() {
        Run run = Logging.current();
        if (run != null) {
            run.out().flush();
            run.err().flush();
        }
    }

    @Override
    public void close() {
        // nothing is kept open between entries
    }
}

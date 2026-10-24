package io.github.deshark42.modpackupdater.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import org.tinylog.Level;
import org.tinylog.core.LogEntry;
import org.tinylog.core.LogEntryValue;
import org.tinylog.writers.Writer;

/**
 * Appends every entry to the run's transcript: the same lines the console shows, plus the DEBUG
 * detail, so a run that moved data leaves behind a file that explains what it did. TRACE entries are
 * the only ones the file does not keep.
 */
public final class TranscriptWriter implements Writer {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public TranscriptWriter(Map<String, String> properties) {
        // configured by Logging; a run's transcript lives in Run, because it changes between runs
    }

    @Override
    public Collection<LogEntryValue> getRequiredLogEntryValues() {
        return EnumSet.of(LogEntryValue.DATE, LogEntryValue.LEVEL, LogEntryValue.MESSAGE,
                LogEntryValue.EXCEPTION);
    }

    @Override
    public void write(LogEntry entry) {
        Run run = Logging.current();
        if (run == null || run.transcript() == null
                || entry.getLevel().ordinal() < Level.DEBUG.ordinal()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append(STAMP.format(entry.getTimestamp().toInstant().atZone(ZoneId.systemDefault())))
                .append(' ')
                .append(String.format(Locale.ROOT, "%5s", entry.getLevel()))
                .append(' ')
                .append(entry.getMessage() == null ? "" : entry.getMessage());
        Throwable error = entry.getException();
        if (error != null) {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            text.append(System.lineSeparator()).append(trace.toString().stripTrailing());
        }
        run.writeTranscript(text.toString());
    }

    @Override
    public void flush() {
        // every entry is flushed as it is written
    }

    @Override
    public void close() {
        // the run closes its own transcript
    }
}

package io.github.deshark42.modpackupdater;

import io.github.deshark42.modpackupdater.log.Logging;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;

/**
 * Captures one run's console output: the two streams a command writes to, plus the logging run that
 * has to be started and finished around it. Close it after the run so the next run starts clean.
 */
public final class TestLogs implements AutoCloseable {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
    private final PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);

    /** Starts the run a command logs to; no transcript is kept. */
    public void begin(Path workspace) {
        Logging.begin(workspace, null, outStream, errStream);
    }

    /** Starts the run a command logs to, keeping a transcript in the workspace's log directory. */
    public void begin(Path workspace, String transcriptName) {
        Logging.begin(workspace, transcriptName, outStream, errStream);
    }

    /** Starts a run whose transcript is dated by {@code clock}, so a test can control the date. */
    public void begin(Path workspace, String transcriptName, Clock clock) {
        Logging.begin(workspace, transcriptName, outStream, errStream, clock);
    }

    /** The stream a command is given for its output. */
    public PrintStream out() {
        return outStream;
    }

    /** The stream a command is given for its errors. */
    public PrintStream err() {
        return errStream;
    }

    /** Everything the run printed to its output stream. */
    public String outText() {
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Everything the run printed to its error stream. */
    public String errText() {
        return err.toString(StandardCharsets.UTF_8);
    }

    /** Ends the run if it is still open and releases the streams. */
    @Override
    public void close() {
        Logging.finish();
        outStream.close();
        errStream.close();
    }
}

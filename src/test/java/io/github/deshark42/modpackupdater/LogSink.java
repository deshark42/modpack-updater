package io.github.deshark42.modpackupdater;

import io.github.deshark42.modpackupdater.log.Log;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

/** Captures what a command prints; {@link #log(Log.Level, java.util.function.LongSupplier)} hands out a Log with a clock a test controls. */
public final class LogSink {

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    public PrintStream out() {
        return out;
    }

    public PrintStream err() {
        return err;
    }

    public String outText() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    public String errText() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    public Log log() {
        return new Log(out);
    }

    public Log log(Log.Level level) {
        return new Log(out, level);
    }

    public Log log(Log.Level level, LongSupplier clock) {
        return new Log(out, level, false, clock);
    }
}

package io.github.deshark42.modpackupdater.log;

import io.github.deshark42.modpackupdater.fs.PathGuard;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts and ends the run being logged: the console streams, the transcript the run leaves behind,
 * and the bar a terminal gets.
 *
 * <p><strong>One run at a time.</strong> The writers themselves are configured in
 * {@code tinylog.properties}, because tinylog applies and freezes its configuration as soon as the
 * first logger is created, which can happen before this class runs. A run's streams and transcript
 * therefore live in {@link Run}, which the writers read on every entry. That is what lets a test
 * drive several runs in one JVM.
 *
 * <p>The console shows INFO and above. The transcript keeps DEBUG as well, so a normal run prints
 * the milestones while the file kept beside it explains them in detail.
 */
public final class Logging {

    /** Transcripts older than this are removed at the start of the next run. */
    private static final int KEEP_DAYS = 14;

    private static final Logger LOG = LoggerFactory.getLogger(Logging.class);

    private static volatile Run run;

    private Logging() {
    }

    /**
     * Starts the run being logged, dating any transcript by the system clock.
     *
     * @param transcriptName the name a transcript should get, or null for a run that writes none
     */
    public static void begin(Path workspace, String transcriptName, PrintStream out, PrintStream err) {
        begin(workspace, transcriptName, out, err, Clock.systemDefaultZone());
    }

    /** Starts the run being logged; {@code clock} dates the transcript and ages out old ones. */
    public static void begin(Path workspace, String transcriptName, PrintStream out, PrintStream err,
            Clock clock) {
        begin(workspace, transcriptName, out, err, clock, new Progress(err, bar(err)));
    }

    static void begin(Path workspace, String transcriptName, PrintStream out, PrintStream err,
            Clock clock, Progress progress) {
        Run next = new Run(out, err, progress,
                transcriptName == null ? null : open(workspace, transcriptName, clock));
        run = next;
        if (next.transcript() != null) {
            LOG.info("logging this run to {}", Text.relativeTo(workspace, next.transcript()));
        } else if (transcriptName != null) {
            LOG.warn("could not keep a log file for this run under {}",
                    Text.relativeTo(workspace, Layout.logsDir(workspace)));
        }
    }

    /** Ends the run: erases any bar on screen and closes the transcript. Safe to call twice. */
    public static void finish() {
        Run finished = run;
        if (finished == null) {
            return;
        }
        run = null;
        finished.progress().clear();
        finished.closeTranscript();
    }

    /** Erases work in flight from the terminal, if one is on screen. */
    public static void clearProgress() {
        Run current = run;
        if (current != null) {
            current.progress().clear();
        }
    }

    /** Work counted in objects rather than transferred bytes. */
    public static void progress(String verb, long done, long total, String noun) {
        progress(verb, done, total, -1, noun);
    }

    /** Work finished, for the bar drawn only on a terminal. */
    public static void progress(String verb, long done, long total, long bytes, String noun) {
        Run current = run;
        if (current != null) {
            current.progress().update(verb, done, total, bytes, noun);
        }
    }

    /** The run the writers should read, or null between runs. */
    static Run current() {
        return run;
    }

    /** A bar only on a terminal that can take one: piped or captured output gets no frames. */
    private static boolean bar(PrintStream err) {
        return err == System.err && System.console() != null
                && !"dumb".equals(System.getenv("TERM"));
    }

    /** Opens the day's transcript, pruning the ones that have aged out first. */
    private static Run.Transcript open(Path workspace, String name, Clock clock) {
        Path directory = Layout.logsDir(workspace);
        Path file = PathGuard.require(workspace,
                directory.resolve(name + "-" + LocalDate.now(clock) + ".log"));
        try {
            Files.createDirectories(directory);
            prune(workspace, directory, clock);
            return new Run.Transcript(file, Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        } catch (IOException e) {
            return null;
        }
    }

    /** Removes the transcripts older than the retention window; a file that cannot be read is kept. */
    private static void prune(Path workspace, Path directory, Clock clock) {
        long cutoff = clock.millis() - TimeUnit.DAYS.toMillis(KEEP_DAYS);
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                try {
                    if (file.getFileName().toString().endsWith(".log")
                            && Files.getLastModifiedTime(PathGuard.require(workspace, file)).toMillis() < cutoff) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException e) {
                    // a file that cannot be inspected is left alone
                }
            }
        } catch (IOException e) {
            // a directory that cannot be listed simply has nothing to prune
        }
    }
}

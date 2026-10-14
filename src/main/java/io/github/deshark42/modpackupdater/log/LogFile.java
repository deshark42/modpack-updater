package io.github.deshark42.modpackupdater.log;

import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Keeps what a run did on disk, but only for the runs that moved data: a publish leaves one, an
 * update leaves one when it applied something. A dry run, or a run with nothing to transfer, wants
 * no file to read later and writes none.
 *
 * <p>The name carries the moment, the command and the tag, so the files sort by time and say what
 * they are without being opened.
 */
public final class LogFile {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    private LogFile() {
    }

    /** Saves the run's transcript in the workspace and says where it went. */
    public static void keep(Path workspace, String label, Log log) {
        Path directory = Layout.logsDir(workspace);
        Path file = save(directory, label, log.transcript());
        if (file == null) {
            log.note("could not write a log under " + directory);
        } else {
            log.note("wrote " + Log.relativeTo(workspace, file));
        }
    }

    /**
     * Writes the transcript into the given directory and returns the file, or null when the
     * directory cannot be written: a log that cannot be kept is not worth failing a finished
     * transfer over.
     */
    private static Path save(Path directory, String label, List<String> lines) {
        try {
            Files.createDirectories(directory);
            Path file = freeName(directory, STAMP.format(Instant.now()) + "-" + label);
            StringBuilder text = new StringBuilder();
            for (String line : lines) {
                text.append(line).append(System.lineSeparator());
            }
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            return null;
        }
    }

    /** A second run in the same second gets a number rather than overwriting the first one. */
    private static Path freeName(Path directory, String name) {
        Path file = directory.resolve(name + ".log");
        for (int number = 2; Files.exists(file); number++) {
            file = directory.resolve(name + "-" + number + ".log");
        }
        return file;
    }
}

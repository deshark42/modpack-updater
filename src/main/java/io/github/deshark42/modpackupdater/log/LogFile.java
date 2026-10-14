package io.github.deshark42.modpackupdater.log;

import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Keeps what a run did on disk, but only for the runs that moved data: a publish leaves one, an
 * update leaves one when it applied something. A dry run, or a run with nothing to transfer, wants
 * no file to read later and writes none.
 *
 * <p>The file holds the whole transcript, including the closing summary line that names the file
 * itself, so it reads on its own. The name carries the moment, the command and the tag, so the
 * files sort by time and say what they are without being opened.
 */
public final class LogFile {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    private LogFile() {
    }

    /**
     * Saves the run's transcript in the workspace and adds the closing summary line that says
     * where it went. Call it after the other summary lines, as the last thing the run does.
     */
    public static void keep(Path workspace, String label, Log log) {
        Path directory = Layout.logsDir(workspace);
        Path file = allocate(directory, label);
        if (file == null) {
            log.summary("log", "could not be kept under " + Log.relativeTo(workspace, directory));
            return;
        }
        log.summary("log", Log.relativeTo(workspace, file));
        if (!write(file, log)) {
            log.step("log", "could not write " + Log.relativeTo(workspace, file));
        }
    }

    /** The file the transcript should become, or null when the directory cannot be written. */
    private static Path allocate(Path directory, String label) {
        try {
            Files.createDirectories(directory);
            return freeName(directory, STAMP.format(Instant.now()) + "-" + label);
        } catch (IOException e) {
            return null;
        }
    }

    /** Writes the transcript, including the summary line that names this file. */
    private static boolean write(Path file, Log log) {
        StringBuilder text = new StringBuilder();
        for (String line : log.transcript()) {
            text.append(line).append(System.lineSeparator());
        }
        try {
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
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

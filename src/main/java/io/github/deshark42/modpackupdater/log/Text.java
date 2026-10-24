package io.github.deshark42.modpackupdater.log;

import java.nio.file.Path;
import java.util.Locale;

/** The facts a log line carries besides its message: workspace-relative paths, counts, durations. */
public final class Text {

    private Text() {
    }

    /** The path as the user sees it: relative to the workspace the command was pointed at. */
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

    /** "0.4s": how long a stage took. */
    public static String duration(long nanos) {
        return String.format(Locale.ROOT, "%.1fs", nanos / 1e9);
    }

    /** "1m45s", "12.4s": how long the whole run took. */
    public static String elapsed(long nanos) {
        long seconds = Math.round(nanos / 1e9);
        if (seconds < 60) {
            return String.format(Locale.ROOT, "%.1fs", nanos / 1e9);
        }
        return String.format(Locale.ROOT, "%dm%02ds", seconds / 60, seconds % 60);
    }
}

package io.github.deshark42.modpackupdater.fs;

import java.util.Locale;

/** Byte counts and byte rates for the lines the commands print. */
public final class Sizes {

    private static final String[] UNITS = {"KiB", "MiB", "GiB", "TiB"};

    private Sizes() {
    }

    public static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }

    /** "17.5 B/s": a byte count spread over the time it took to move it. */
    public static String rate(long bytes, long nanos) {
        double seconds = nanos / 1e9;
        return human(seconds <= 0 ? bytes : (long) (bytes / seconds)) + "/s";
    }
}

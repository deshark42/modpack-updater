package io.github.deshark42.modpackupdater.model;

import io.github.deshark42.modpackupdater.UserError;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The parameters a chunk table records: a cut may happen only after {@code min} bytes, the rolling
 * hash is expected to hit one every {@code avg} bytes, and {@code max} ends a chunk that never hit
 * one. The mean chunk is therefore {@code min + avg}, not {@code avg}.
 */
public record Chunking(int min, int avg, int max) {

    /** The parameters every publish uses: 8 KiB / 32 KiB / 128 KiB. */
    public static final Chunking DEFAULT = new Chunking(8 * 1024, 32 * 1024, 128 * 1024);

    public Chunking {
        if (min <= 0) {
            throw new UserError("chunking min must be positive: " + min);
        }
        if (avg <= min) {
            throw new UserError("chunking avg must be larger than min: " + avg);
        }
        if (max < avg) {
            throw new UserError("chunking max must not be smaller than avg: " + max);
        }
        if (Integer.bitCount(avg) != 1) {
            throw new UserError("chunking avg must be a power of two: " + avg);
        }
    }

    /** The mask that makes a cut happen once every {@code avg} bytes of content. */
    public long mask() {
        return avg - 1L;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("min", (long) min);
        out.put("avg", (long) avg);
        out.put("max", (long) max);
        return out;
    }

    static Chunking parse(Map<String, Object> object, String what) {
        return new Chunking(size(object, "min", what), size(object, "avg", what), size(object, "max", what));
    }

    private static int size(Map<String, Object> object, String key, String what) {
        long value = Fields.longValue(object, key, what);
        if (value < 1 || value > Integer.MAX_VALUE) {
            throw new UserError(what + ": \"" + key + "\" is out of range: " + value);
        }
        return (int) value;
    }
}

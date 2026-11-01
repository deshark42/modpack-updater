package io.github.deshark42.modpackupdater.model;

import io.github.deshark42.modpackupdater.UserError;

/** Object keys in the bucket, relative to the configured prefix. */
public final class ObjectKeys {

    public static final String BLOB = "blob";
    public static final String INDEX = "index";
    public static final String METADATA = "metadata";
    public static final String DEFAULT_CHANNEL = "latest";

    /**
     * The Cache-Control of an object that is only ever added, never rewritten or deleted: content
     * addressed blobs and indexes, and each release's manifest and detached signature.
     */
    public static final String IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable";

    /** The Cache-Control of mutable channel pointers. */
    public static final String NO_STORE_CACHE_CONTROL = "no-store";

    private ObjectKeys() {
    }

    /**
     * blob/<first byte of the hash>/<chunk hash>: content addressing stores each distinct chunk
     * once, and the first byte keeps any single listing from turning into one huge flat directory.
     */
    public static String blob(String hash) {
        return BLOB + "/" + shard(hash) + "/" + hash;
    }

    /** index/<first byte of the id>/<index id>: one chunk table per distinct file content. */
    public static String index(String hash) {
        return INDEX + "/" + shard(hash) + "/" + hash;
    }

    public static String release(String tag, String releaseId) {
        return METADATA + "/" + Names.requireTag(tag) + "/releases/" + Names.requireReleaseId(releaseId) + ".json";
    }

    public static String latest(String tag) {
        return channel(tag, DEFAULT_CHANNEL);
    }

    public static String channel(String tag, String channel) {
        return METADATA + "/" + Names.requireTag(tag) + "/" + Names.requireChannel(channel) + ".json";
    }

    public static String signature(String tag, String releaseId) {
        return release(tag, releaseId) + ".sig";
    }

    /**
     * How long caches may keep the object under this key, or null for a kind whose caching has not
     * been decided: no kind is immutable by default, so a new one has to say so here.
     */
    public static String cacheControl(String key) {
        if (isChannelPointer(key)) {
            return NO_STORE_CACHE_CONTROL;
        }
        if (key.startsWith(BLOB + "/") || key.startsWith(INDEX + "/")
                || (key.startsWith(METADATA + "/") && key.contains("/releases/")
                && (key.endsWith(".json") || key.endsWith(".json.sig")))) {
            return IMMUTABLE_CACHE_CONTROL;
        }
        return null;
    }

    private static boolean isChannelPointer(String key) {
        String[] parts = key.split("/", -1);
        if (parts.length != 3 || !parts[0].equals(METADATA) || !parts[2].endsWith(".json")) {
            return false;
        }
        try {
            Names.requireTag(parts[1]);
            Names.requireChannel(parts[2].substring(0, parts[2].length() - ".json".length()));
            return true;
        } catch (UserError e) {
            return false;
        }
    }

    private static String shard(String hash) {
        Manifest.requireHash(hash);
        return hash.substring(0, 2);
    }
}

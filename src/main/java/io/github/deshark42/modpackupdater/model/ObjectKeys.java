package io.github.deshark42.modpackupdater.model;

/** Object keys in the bucket, relative to the configured prefix. */
public final class ObjectKeys {

    public static final String BLOB = "blob";
    public static final String INDEX = "index";
    public static final String METADATA = "metadata";

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
        return METADATA + "/" + Names.requireTag(tag) + "/latest.json";
    }

    private static String shard(String hash) {
        Manifest.requireHash(hash);
        return hash.substring(0, 2);
    }
}

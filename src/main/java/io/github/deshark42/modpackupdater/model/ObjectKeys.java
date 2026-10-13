package io.github.deshark42.modpackupdater.model;

/** Object keys in the bucket, relative to the configured prefix. */
public final class ObjectKeys {

    public static final String BLOBS = "blobs";
    public static final String METADATA = "metadata";

    private ObjectKeys() {
    }

    /**
     * blobs/<first byte of the hash>/<hash>: content addressing stores each distinct file once, and
     * the first byte keeps any single listing from turning into one huge flat directory.
     */
    public static String blob(String hash) {
        Manifest.requireHash(hash);
        return BLOBS + "/" + hash.substring(0, 2) + "/" + hash;
    }

    public static String release(String tag, String releaseId) {
        return METADATA + "/" + Names.requireTag(tag) + "/releases/" + Names.requireReleaseId(releaseId) + ".json";
    }

    public static String latest(String tag) {
        return METADATA + "/" + Names.requireTag(tag) + "/latest.json";
    }
}

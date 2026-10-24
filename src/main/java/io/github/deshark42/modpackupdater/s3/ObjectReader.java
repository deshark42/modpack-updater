package io.github.deshark42.modpackupdater.s3;

import java.nio.file.Path;

/**
 * The read side of a bucket: object keys relative to the prefix, and the two ways a run fetches
 * what they name. A store that also writes implements more than this.
 */
public interface ObjectReader {

    /** The object key of a workspace relative path, prefix included. */
    String key(String relative);

    /** An object's bytes, inflated when it is stored gzipped. */
    byte[] getBytes(String relative);

    /** Fetches an object into {@code target}, which is replaced only once the download is complete. */
    void download(String relative, Path target);
}

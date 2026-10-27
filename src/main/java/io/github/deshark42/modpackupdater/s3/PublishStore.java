package io.github.deshark42.modpackupdater.s3;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.ObjectKeys;

/** The publisher's remote precondition and successful-upload confirmation boundary. */
public final class PublishStore {
    private final S3Store store;
    private final BlobCache cache;

    public PublishStore(S3Store store, BlobCache cache) {
        this.store = store;
        this.cache = cache;
    }

    public static void requireNewRelease(S3Store store, String tag, String releaseId) {
        if (store.exists(ObjectKeys.release(tag, releaseId))) {
            throw new UserError("release " + releaseId + " of tag " + tag
                    + " already exists; a published release is never rewritten");
        }
    }

    public void putChunk(String hash, byte[] bytes) {
        store.putBytes(ObjectKeys.blob(hash), bytes);
        cache.remember(hash);
    }

    public void putIndex(String hash, byte[] bytes) {
        store.putBytes(ObjectKeys.index(hash), bytes);
        cache.remember(hash);
    }
}

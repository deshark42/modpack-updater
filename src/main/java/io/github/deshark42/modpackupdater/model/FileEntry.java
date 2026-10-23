package io.github.deshark42.modpackupdater.model;

import io.github.deshark42.modpackupdater.UserError;

/** One tracked file: the sha256 of its whole content, its size, and the id of its chunk table. */
public record FileEntry(String hash, long size, String indexId) {

    public FileEntry {
        Manifest.requireHash(hash);
        Manifest.requireIndexId(indexId);
        if (size < 0) {
            throw new UserError("a manifest entry has a negative size: " + size);
        }
    }
}

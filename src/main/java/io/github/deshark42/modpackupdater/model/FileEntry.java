package io.github.deshark42.modpackupdater.model;

import io.github.deshark42.modpackupdater.UserError;

/** One tracked file: the whole-content hash its chunks add up to, its size, and its chunk table id. */
public record FileEntry(String hash, long size, String chunks) {

    public FileEntry {
        Manifest.requireHash(hash);
        Manifest.requireChunks(chunks);
        if (size < 0) {
            throw new UserError("a manifest entry has a negative size: " + size);
        }
    }
}

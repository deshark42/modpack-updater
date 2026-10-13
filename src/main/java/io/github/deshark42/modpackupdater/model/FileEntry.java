package io.github.deshark42.modpackupdater.model;

import io.github.deshark42.modpackupdater.UserError;

/** One tracked file: the content hash its blob is named after, and its size in bytes. */
public record FileEntry(String hash, long size) {
    public FileEntry {
        if (hash == null || hash.isEmpty()) {
            throw new UserError("a manifest entry has no hash");
        }
        if (size < 0) {
            throw new UserError("a manifest entry has a negative size: " + size);
        }
    }
}

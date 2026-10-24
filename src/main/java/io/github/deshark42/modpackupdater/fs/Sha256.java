package io.github.deshark42.modpackupdater.fs;

import io.github.deshark42.modpackupdater.UserError;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Content hashes of chunks and of whole files. */
public final class Sha256 {

    private static final int BUFFER = 1 << 16;

    private Sha256() {
    }

    public static String ofBytes(byte[] content) {
        return hex(newDigest().digest(content));
    }

    /**
     * Hashes a whole file in one streaming pass. Reading a file into memory first would fail on the
     * large jars a modpack carries, so the digest is fed one buffer at a time.
     */
    public static String ofFile(Path file) {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[BUFFER];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), BUFFER)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new UserError("cannot read " + file + ": " + e.getMessage(), e);
        }
        return hex(digest.digest());
    }

    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}

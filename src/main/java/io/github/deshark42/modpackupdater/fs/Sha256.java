package io.github.deshark42.modpackupdater.fs;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Content hashes of chunks and of whole files. */
public final class Sha256 {

    private Sha256() {
    }

    public static String ofBytes(byte[] content) {
        return hex(newDigest().digest(content));
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

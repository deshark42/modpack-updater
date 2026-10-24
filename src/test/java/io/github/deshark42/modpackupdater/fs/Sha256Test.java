package io.github.deshark42.modpackupdater.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.deshark42.modpackupdater.TestFiles;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Test;

class Sha256Test {

    /** The published SHA-256 vector for the empty input. */
    private static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    /** The published SHA-256 vector for "abc". */
    private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void hashesTheEmptyInputToItsKnownVector() {
        assertEquals(EMPTY, Sha256.ofBytes(new byte[0]));
    }

    @Test
    void hashesAbcToItsKnownVector() {
        assertEquals(ABC, Sha256.ofBytes(TestFiles.bytes("abc")));
    }

    @Test
    void formatsBytesAsLowercaseHexWithLeadingZeros() {
        assertEquals("0a1fff00", Sha256.hex(new byte[]{0x0a, 0x1f, (byte) 0xff, 0x00}));
    }

    @Test
    void ofBytesIsTheHexOfTheJdkDigest() {
        byte[] content = TestFiles.bytes("chunk boundary bytes");

        assertEquals(Sha256.hex(digest(content)), Sha256.ofBytes(content));
    }

    private static byte[] digest(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is not available", e);
        }
    }
}

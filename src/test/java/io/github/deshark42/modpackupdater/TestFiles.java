package io.github.deshark42.modpackupdater;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/** Fixture files: content written into a temp workspace, and deterministic bytes for size-sensitive tests. */
public final class TestFiles {

    private TestFiles() {
    }

    public static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    public static void write(Path root, String name, String content) throws IOException {
        write(root, name, bytes(content));
    }

    public static void write(Path root, String name, byte[] content) throws IOException {
        Path file = root.resolve(name);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, content);
    }

    /** Deterministic bytes, so a multi-chunk fixture does not depend on an unseeded generator. */
    public static byte[] random(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }
}

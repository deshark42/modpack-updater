package io.github.deshark42.modpackupdater.fs;

import io.github.deshark42.modpackupdater.UserError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Reads and writes small text files so that readers never observe a half-written file. */
public final class AtomicFile {

    private AtomicFile() {
    }

    public static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UserError("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    public static void write(Path file, String content) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.isDirectory(file)) {
                throw new UserError("cannot write " + file + ": it is a directory");
            }
            Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            try {
                Files.writeString(temp, content, StandardCharsets.UTF_8);
                move(temp, file);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UserError("cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    /** Moves a fully written temporary file into place; the target is never half written. */
    public static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

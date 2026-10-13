package io.github.deshark42.modpackupdater.fs;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.FileEntry;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.TreeMap;

/** Walks a directory and records every regular file as a path-relative entry. */
public final class Tree {

    private Tree() {
    }

    public static Map<String, FileEntry> scan(Path root) {
        if (!Files.isDirectory(root)) {
            throw new UserError("no such directory: " + root);
        }
        Map<String, FileEntry> files = new TreeMap<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isRegularFile()) {
                        files.put(relative(root, file), new FileEntry(Sha256.ofFile(file), attrs.size()));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UserError("cannot read " + root + ": " + e.getMessage());
        }
        return files;
    }

    private static String relative(Path root, Path file) {
        StringBuilder out = new StringBuilder();
        for (Path part : root.relativize(file)) {
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(part);
        }
        return out.toString();
    }
}

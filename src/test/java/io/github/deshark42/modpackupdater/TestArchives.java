package io.github.deshark42.modpackupdater;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.tukaani.xz.LZMA2Options;

/** Ordinary archive fixtures; ordered entries intentionally permit duplicate names. */
public final class TestArchives {
    private static final LocalDateTime ZIP_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);

    public record Entry(String name, byte[] content) {
        public boolean directory() {
            return name.endsWith("/");
        }
    }

    private TestArchives() {
    }

    public static Path write(Path archive, Map<String, byte[]> entries) throws IOException {
        return write(archive, new TreeMap<>(entries).entrySet().stream()
                .map(entry -> new Entry(entry.getKey(), entry.getValue())).toList());
    }

    public static Path write(Path archive, List<Entry> entries) throws IOException {
        Files.createDirectories(archive.getParent());
        String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) {
            zip(archive, entries);
        } else if (name.endsWith(".7z")) {
            sevenZ(archive, entries);
        } else {
            throw new IllegalArgumentException("unsupported fixture archive: " + archive);
        }
        return archive;
    }

    private static void zip(Path archive, List<Entry> entries) throws IOException {
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(Files.newOutputStream(archive))) {
            out.setEncoding(StandardCharsets.UTF_8.name());
            out.setUseLanguageEncodingFlag(true);
            for (Entry entry : entries) {
                ZipArchiveEntry item = new ZipArchiveEntry(entry.name());
                item.setTimeLocal(ZIP_TIME);
                out.putArchiveEntry(item);
                out.write(entry.content());
                out.closeArchiveEntry();
            }
        }
    }

    private static void sevenZ(Path archive, List<Entry> entries) throws IOException {
        try (SevenZOutputFile out = new SevenZOutputFile(archive.toFile())) {
            out.setContentMethods(List.of(new SevenZMethodConfiguration(SevenZMethod.LZMA2, new LZMA2Options(0))));
            for (Entry entry : entries) {
                SevenZArchiveEntry item = new SevenZArchiveEntry();
                item.setName(entry.name());
                item.setDirectory(entry.directory());
                item.setSize(entry.content().length);
                out.putArchiveEntry(item);
                out.write(entry.content());
                out.closeArchiveEntry();
            }
        }
    }
}

package io.github.deshark42.modpackupdater.fs;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.zip.AsiExtraField;
import org.apache.commons.compress.archivers.zip.UnicodePathExtraField;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipExtraField;
import org.apache.commons.compress.archivers.zip.ZipFile;

/** Materializes one publisher archive until all scans and random chunk reads have finished. */
public final class BaseArchive implements AutoCloseable {

    private static final int SEVEN_Z_MEMORY_LIMIT_KIB = 256 * 1024;
    private static final int WINDOWS_DEVICE_OR_REPARSE_POINT = 0x40 | 0x400;
    private static final int WINDOWS_UNIX_EXTENSION = 0x8000;

    private final Path workspace;
    private final Path archive;
    private final Path root;
    private final PathGuard guard;
    private final Set<String> entries = new HashSet<>();
    private final Map<String, Boolean> directories = new HashMap<>();
    private final byte[] buffer = new byte[64 * 1024];
    private boolean closed;

    private BaseArchive(Path workspace, Path archive, Path root) {
        this.workspace = workspace;
        this.archive = archive;
        this.root = root;
        this.guard = PathGuard.at(workspace);
    }

    public static BaseArchive open(Path workspace, Path archive) {
        Path stage = null;
        try {
            PathGuard.require(workspace, archive);
            if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("not a regular archive file");
            }
            Path temp = Layout.tempDir(workspace);
            Files.createDirectories(temp);
            PathGuard.require(workspace, temp);
            stage = Files.createTempDirectory(temp, "base-archive-");
            PathGuard.require(workspace, stage);
            BaseArchive result = new BaseArchive(workspace, archive, stage);
            String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".zip")) {
                result.extractZip();
            } else if (name.endsWith(".7z")) {
                result.extractSevenZ();
            } else {
                throw new IOException("unsupported archive format (expected ZIP or 7z)");
            }
            return result;
        } catch (IOException | RuntimeException | LinkageError cause) {
            UserError error = new UserError("cannot extract base archive " + archive + ": " + cause.getMessage(), cause);
            if (stage != null) {
                try {
                    deleteStage(workspace, stage);
                } catch (IOException | RuntimeException cleanup) {
                    error.addSuppressed(cleanup);
                }
            }
            throw error;
        }
    }

    public Path root() {
        return root;
    }

    private void extractZip() throws IOException {
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            var all = zip.getEntriesInPhysicalOrder();
            while (all.hasMoreElements()) {
                ZipArchiveEntry entry = all.nextElement();
                if (entry.getGeneralPurposeBit().usesEncryption() || !zip.canReadEntryData(entry)) {
                    throw new IOException("encrypted or unreadable ZIP entry: " + entry.getName());
                }
                String name = entryName(entry);
                requireType(entry.getUnixMode(), entry.isDirectory(), name);
                if ((entry.getExternalAttributes() & WINDOWS_DEVICE_OR_REPARSE_POINT) != 0) {
                    throw new IOException("link or special ZIP entry: " + name);
                }
                for (var extra : entry.getExtraFields()) {
                    if (extra instanceof AsiExtraField asi && asi.isLink()) {
                        throw new IOException("link ZIP entry: " + name);
                    }
                }
                // FAT ZIP names may already have been normalized by Commons Compress.
                byte[] rawName = entry.getRawName();
                if (rawName != null) {
                    for (byte b : rawName) {
                        if (b == '\\') {
                            throw new IOException("unsafe entry path: " + name);
                        }
                    }
                }
                if (entry.getCrc() < 0) {
                    throw new IOException("missing ZIP checksum: " + name);
                }
                Path target = prepare(name, entry.isDirectory());
                try (InputStream in = zip.getInputStream(entry)) {
                    write(target, entry.isDirectory(), entry.getSize(), entry.getCrc(), in::read);
                }
            }
        }
    }

    private void extractSevenZ() throws IOException {
        try (SevenZFile sevenZ = SevenZFile.builder().setPath(archive)
                .setMaxMemoryLimitKiB(SEVEN_Z_MEMORY_LIMIT_KIB)
                .setTryToRecoverBrokenArchives(false).setUseDefaultNameForUnnamedEntries(false).get()) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZ.getNextEntry()) != null) {
                if (entry.isAntiItem()) {
                    throw new IOException("7z anti-item is not a file: " + entry.getName());
                }
                if (entry.getHasWindowsAttributes()) {
                    int attributes = entry.getWindowsAttributes();
                    if ((attributes & WINDOWS_DEVICE_OR_REPARSE_POINT) != 0) {
                        throw new IOException("link or special 7z entry: " + entry.getName());
                    }
                    if ((attributes & WINDOWS_UNIX_EXTENSION) != 0) {
                        requireType(attributes >>> 16, entry.isDirectory(), entry.getName());
                    }
                }
                if (entry.getContentMethods() != null) {
                    for (var method : entry.getContentMethods()) {
                        if (method.getMethod() == SevenZMethod.AES256SHA256) {
                            throw new IOException("encrypted 7z entry: " + entry.getName());
                        }
                    }
                }
                Path target = prepare(entry.getName(), entry.isDirectory());
                write(target, entry.isDirectory(), entry.getSize(), entry.getHasCrc() ? entry.getCrcValue() : -1,
                        sevenZ::read);
            }
        }
    }

    /**
     * Returns the entry name only when its bytes decode as UTF-8. Commons Compress decodes names
     * without the UTF-8 flag through its default UTF-8 charset, replacing undecodable bytes, so an
     * unchecked name would publish mangled paths instead of failing. The Unicode path extra field
     * needs the same check because Commons Compress decodes it the same way.
     */
    private static String entryName(ZipArchiveEntry entry) throws IOException {
        String name = entry.getName();
        if (entry.getNameSource() == ZipArchiveEntry.NameSource.UNICODE_EXTRA_FIELD) {
            // The stored name is legacy-encoded and this field carries the real one.
            byte[] unicodeName = unicodeName(entry);
            if (unicodeName == null) {
                throw new IOException("ZIP entry name has no readable Unicode name: " + name);
            }
            return utf8(unicodeName, name);
        }
        byte[] rawName = entry.getRawName();
        if (rawName == null || isAscii(rawName)) {
            return name;
        }
        return utf8(rawName, name);
    }

    private static byte[] unicodeName(ZipArchiveEntry entry) {
        for (ZipExtraField extra : entry.getExtraFields()) {
            if (extra instanceof UnicodePathExtraField unicodePath) {
                return unicodePath.getUnicodeName();
            }
        }
        return null;
    }

    private static String utf8(byte[] bytes, String name) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("ZIP entry name is not UTF-8: " + name
                    + " (legacy-encoded names are rejected; re-create the archive with UTF-8 names)", e);
        }
    }

    private static boolean isAscii(byte[] bytes) {
        for (byte b : bytes) {
            if (b < 0) {
                return false;
            }
        }
        return true;
    }

    private static void requireType(int mode, boolean directory, String name) throws IOException {
        int type = mode & UnixStat.FILE_TYPE_FLAG;
        if (type != 0 && type != (directory ? UnixStat.DIR_FLAG : UnixStat.FILE_FLAG)) {
            throw new IOException("link or special archive entry: " + name);
        }
    }

    private Path prepare(String name, boolean directory) throws IOException {
        String relative = relative(name, directory);
        if (!entries.add(relative)) {
            throw new IOException("duplicate archive entry: " + name);
        }
        Boolean existing = directories.get(relative);
        if (existing != null && (!existing || !directory)) {
            throw new IOException("file-directory collision: " + name);
        }
        int slash = relative.indexOf('/');
        while (slash >= 0) {
            String parent = relative.substring(0, slash);
            if (Boolean.FALSE.equals(directories.putIfAbsent(parent, true))) {
                throw new IOException("file-directory collision: " + name);
            }
            slash = relative.indexOf('/', slash + 1);
        }
        directories.put(relative, directory);
        return root.resolve(relative);
    }

    private static String relative(String name, boolean directory) throws IOException {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) {
            throw new IOException("unsafe entry path: " + name);
        }
        String relative = directory && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (String part : relative.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IOException("unsafe entry path: " + name);
            }
        }
        try {
            Path.of(relative);
        } catch (InvalidPathException e) {
            throw new IOException("unsafe entry path: " + name, e);
        }
        return relative;
    }

    @FunctionalInterface
    private interface EntryReader {
        int read(byte[] buffer) throws IOException;
    }

    private void write(Path target, boolean directory, long size, long checksum, EntryReader reader) throws IOException {
        if (size < 0 || (directory && size != 0)) {
            throw new IOException("invalid entry size: " + root.relativize(target));
        }
        guard.require(target);
        Files.createDirectories(directory ? target : target.getParent());
        guard.require(target);
        CRC32 crc = new CRC32();
        long count = 0;
        try (OutputStream out = directory ? OutputStream.nullOutputStream()
                : Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS)) {
            int read;
            while ((read = reader.read(buffer)) != -1) {
                count += read;
                if (count > size) {
                    throw new IOException("entry size mismatch: " + root.relativize(target));
                }
                crc.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        guard.require(target);
        if (count != size || (checksum >= 0 && crc.getValue() != checksum)) {
            throw new IOException("entry size or checksum mismatch: " + root.relativize(target));
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        try {
            deleteStage(workspace, root);
            closed = true;
        } catch (IOException | RuntimeException e) {
            throw new UserError("cannot clean up base archive " + archive + ": " + e.getMessage(), e);
        }
    }

    private static void deleteStage(Path workspace, Path stage) throws IOException {
        PathGuard guard = PathGuard.at(workspace);
        guard.require(stage);
        if (!Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        // PathGuard, like the rest of the workspace I/O, does not lock out concurrent replacements.
        Files.walkFileTree(stage, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                guard.require(directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                guard.require(file.getParent());
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) {
                    throw error;
                }
                guard.require(directory);
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

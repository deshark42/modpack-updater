package io.github.deshark42.modpackupdater.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.deshark42.modpackupdater.TestArchives;
import io.github.deshark42.modpackupdater.TestArchives.Entry;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.zip.UnicodePathExtraField;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class BaseArchiveTest {

    private static Entry file(String name, String content) {
        return new Entry(name, TestFiles.bytes(content));
    }

    private static Entry directory(String name) {
        return new Entry(name, new byte[0]);
    }

    private static Path archive(Path workspace, String format, Entry... entries) throws IOException {
        return TestArchives.write(workspace.resolve("base." + format), List.of(entries));
    }

    private static Path unsafeNameZip(Path workspace, String name) throws IOException {
        Path archive = workspace.resolve("base.zip");
        // Commons ZIP entries normalize backslashes before writing; these fixtures must retain the raw name.
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            for (Entry entry : List.of(file("good", "already extracted"), file(name, "bad"))) {
                ZipEntry item = new ZipEntry(entry.name());
                item.setTimeLocal(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
                out.putNextEntry(item);
                out.write(entry.content());
                out.closeEntry();
            }
        }
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8);
                var entries = zip.stream()) {
            assertEquals(List.of("good", name), entries.map(ZipEntry::getName).toList());
        }
        return archive;
    }

    private static Path zip(Path workspace, ZipArchiveEntry... entries) throws IOException {
        Path archive = workspace.resolve("base.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(archive.toFile())) {
            for (ZipArchiveEntry entry : entries) {
                entry.setTimeLocal(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
                out.putArchiveEntry(entry);
                out.closeArchiveEntry();
            }
        }
        return archive;
    }

    private static Path sevenZ(Path workspace, Consumer<SevenZArchiveEntry> configure) throws IOException {
        Path archive = workspace.resolve("base.7z");
        try (SevenZOutputFile out = new SevenZOutputFile(archive.toFile())) {
            SevenZArchiveEntry entry = new SevenZArchiveEntry();
            entry.setName("entry");
            configure.accept(entry);
            out.putArchiveEntry(entry);
            out.closeArchiveEntry();
        }
        return archive;
    }

    /**
     * Rewrites the first two name bytes of a stored ZIP entry, keeping every header length intact,
     * so the entry still parses but its name is no longer valid UTF-8.
     */
    private static Path legacyNameZip(Path workspace) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(buffer)) {
            ZipEntry entry = new ZipEntry("AB/mods.txt");
            entry.setTimeLocal(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
            out.putNextEntry(entry);
            out.write(TestFiles.bytes("content"));
            out.closeEntry();
        }
        byte[] zip = buffer.toByteArray();
        byte[] placeholder = "AB/mods.txt".getBytes(StandardCharsets.US_ASCII);
        byte[] legacy = placeholder.clone();
        legacy[0] = (byte) 0xD6;
        legacy[1] = (byte) 0xD0;
        int patched = 0;
        for (int start = 0; start <= zip.length - placeholder.length; start++) {
            if (Arrays.equals(zip, start, start + placeholder.length, placeholder, 0, placeholder.length)) {
                System.arraycopy(legacy, 0, zip, start, legacy.length);
                patched++;
                start += placeholder.length - 1;
            }
        }
        assertEquals(2, patched, "the name appears in the local header and the central directory");
        Path archive = workspace.resolve("base.zip");
        Files.write(archive, zip);
        return archive;
    }

    private static void rejectsAndCleans(Path workspace, Path archive, String reason) throws IOException {
        Path temp = Layout.tempDir(workspace);
        TestFiles.write(temp, "blob/keep", "blob");
        TestFiles.write(temp, "file/keep", "file");
        TestFiles.write(temp, "previous/keep", "previous");
        TestFiles.write(workspace, "base/keep", "base");
        TestFiles.write(workspace, "overlay/keep", "overlay");
        Map<String, Long> before = Tree.list(workspace);
        byte[] original = Files.readAllBytes(archive);

        UserError error = assertThrows(UserError.class, () -> {
            try (BaseArchive ignored = BaseArchive.open(workspace, archive)) {
                // A mistakenly accepted archive must still release its staging resources.
            }
        });

        assertTrue(error.getMessage().contains(archive.toString()), error.getMessage());
        assertTrue(error.getMessage().toLowerCase(Locale.ROOT).contains(reason.toLowerCase(Locale.ROOT)), error.getMessage());
        assertEquals(before, Tree.list(workspace));
        assertArrayEquals(original, Files.readAllBytes(archive));
        assertEquals("blob", Files.readString(temp.resolve("blob/keep")));
        assertEquals("file", Files.readString(temp.resolve("file/keep")));
        assertEquals("previous", Files.readString(temp.resolve("previous/keep")));
        assertEquals("base", Files.readString(workspace.resolve("base/keep")));
        assertEquals("overlay", Files.readString(workspace.resolve("overlay/keep")));
        try (var remaining = Files.list(temp)) {
            assertEquals(List.of("blob", "file", "previous"), remaining.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void preservesTheArchiveRootAndStreamsOrdinaryFilesForZip(@TempDir Path workspace) throws IOException {
        preservesTheArchiveRootAndStreamsOrdinaryFiles("zip", workspace);
    }

    @Test
    void preservesTheArchiveRootAndStreamsOrdinaryFilesForSevenZ(@TempDir Path workspace) throws IOException {
        preservesTheArchiveRootAndStreamsOrdinaryFiles("7z", workspace);
    }

    private static void preservesTheArchiveRootAndStreamsOrdinaryFiles(String format, Path workspace) throws IOException {
        byte[] content = TestFiles.random(131_073, 81);
        Path archive = archive(workspace, format, new Entry("wrapper/mods/a.jar", content),
                file("wrapper/empty", ""), file("wrapper/embedded.zip", "not recursively expanded"),
                directory("wrapper/mods/"), directory("wrapper/"));
        byte[] original = Files.readAllBytes(archive);
        Path extracted;

        try (BaseArchive base = BaseArchive.open(workspace, archive)) {
            extracted = base.root();
            assertEquals(Layout.tempDir(workspace), extracted.getParent());
            assertEquals(Map.of("wrapper/mods/a.jar", (long) content.length, "wrapper/empty", 0L,
                    "wrapper/embedded.zip", 24L), Tree.list(extracted));
            assertArrayEquals(content, Files.readAllBytes(extracted.resolve("wrapper/mods/a.jar")));
            assertEquals("not recursively expanded", Files.readString(extracted.resolve("wrapper/embedded.zip")));
        }

        assertFalse(Files.exists(extracted));
        assertArrayEquals(original, Files.readAllBytes(archive));
        assertFalse(Files.exists(workspace.resolve("base")));
        assertFalse(Files.exists(workspace.resolve("overlay")));
    }

    @Test
    void leavesReservedRootStateForThePublishRemovalRulesForZip(@TempDir Path workspace) throws IOException {
        leavesReservedRootStateForThePublishRemovalRules("zip", workspace);
    }

    @Test
    void leavesReservedRootStateForThePublishRemovalRulesForSevenZ(@TempDir Path workspace) throws IOException {
        leavesReservedRootStateForThePublishRemovalRules("7z", workspace);
    }

    private static void leavesReservedRootStateForThePublishRemovalRules(String format, Path workspace) throws IOException {
        Path archive = archive(workspace, format, file(Layout.STATE_DIR + "/config.json", "downloaded state"));
        try (BaseArchive base = BaseArchive.open(workspace, archive)) {
            assertEquals("downloaded state", Files.readString(base.root().resolve(Layout.STATE_DIR).resolve("config.json")));
        }
    }

    @Test
    void materializesAnEmptyArchiveForZip(@TempDir Path workspace) throws IOException {
        materializesAnEmptyArchive("zip", workspace);
    }

    @Test
    void materializesAnEmptyArchiveForSevenZ(@TempDir Path workspace) throws IOException {
        materializesAnEmptyArchive("7z", workspace);
    }

    private static void materializesAnEmptyArchive(String format, Path workspace) throws IOException {
        Path archive = archive(workspace, format);
        try (BaseArchive base = BaseArchive.open(workspace, archive)) {
            assertEquals(Map.of(), Tree.list(base.root()));
        }
    }

    @Test
    void closesOnlyItsOwnStagingDirectory(@TempDir Path workspace) throws IOException {
        Path archive = archive(workspace, "zip", file("a", "content"));
        TestFiles.write(Layout.tempDir(workspace), "blob/keep", "unrelated update");
        try (BaseArchive first = BaseArchive.open(workspace, archive);
                BaseArchive second = BaseArchive.open(workspace, archive)) {
            assertNotEquals(first.root(), second.root());
            first.close();
            assertFalse(Files.exists(first.root()));
            assertEquals("content", Files.readString(second.root().resolve("a")));
            assertEquals("unrelated update", Files.readString(Layout.blobTempDir(workspace).resolve("keep")));
        }
        assertEquals(Map.of("blob/keep", 16L), Tree.list(Layout.tempDir(workspace)));
    }

    @Test
    void closingAnAlreadyClosedArchiveLeavesOtherStagingUntouched(@TempDir Path workspace) throws IOException {
        Path archive = archive(workspace, "zip", file("a", "content"));
        TestFiles.write(Layout.tempDir(workspace), "blob/keep", "unrelated update");
        try (BaseArchive base = BaseArchive.open(workspace, archive)) {
            base.close();

            base.close();

            assertFalse(Files.exists(base.root()));
            assertEquals(Map.of("blob/keep", 16L), Tree.list(Layout.tempDir(workspace)));
            assertEquals("unrelated update", Files.readString(Layout.blobTempDir(workspace).resolve("keep")));
        }
    }

    private static Stream<Arguments> unsafeNames() {
        return Stream.of("zip", "7z").flatMap(format -> Stream.of("../escaped", "/escaped", "C:/escaped",
                "mods\\escaped", "mods/../escaped", "./escaped", "mods/./escaped", "mods//escaped", "")
                .map(name -> Arguments.of(format, name)));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("unsafeNames")
    void rejectsNonStructuralRelativeNamesWithoutLeavingExtractedFiles(String format, String name,
            @TempDir Path workspace) throws IOException {
        Path archive = format.equals("zip") ? unsafeNameZip(workspace, name)
                : archive(workspace, format, file("good", "already extracted"), file(name, "bad"));
        rejectsAndCleans(workspace, archive, "unsafe entry path");
    }

    private static Stream<Arguments> windowsNames() {
        return Stream.of("zip", "7z").flatMap(format -> Stream.of("NUL", "nul.txt", ".minecraft/NUL.txt",
                "NUL .txt", "nested/AUX.cfg", "CON", "PRN.txt", "COM1", "lPt9.log", "COM\u00b9.txt",
                "LPT\u00b2", "COM\u00b3", "CONIN$", "CONOUT$.log", "name.", "name ",
                "folder./child.txt", "folder /child.txt")
                .map(name -> Arguments.of(format, name)));
    }

    @ParameterizedTest(name = "Windows {0}: {1}")
    @MethodSource("windowsNames")
    @EnabledOnOs(OS.WINDOWS)
    void rejectsWindowsDevicesAndNormalizedNamesBeforeTheyCanDisappear(String format, String name,
            @TempDir Path workspace) throws IOException {
        Path archive = archive(workspace, format, file("good", "already extracted"), file(name, "must not disappear"));

        rejectsAndCleans(workspace, archive, "unsafe entry path");
    }

    @Test
    void preservesOrdinaryNamesThatResembleWindowsDevicesOrShortNamesForZip(@TempDir Path workspace) throws IOException {
        preservesOrdinaryNamesThatResembleWindowsDevicesOrShortNames("zip", workspace);
    }

    @Test
    void preservesOrdinaryNamesThatResembleWindowsDevicesOrShortNamesForSevenZ(@TempDir Path workspace) throws IOException {
        preservesOrdinaryNamesThatResembleWindowsDevicesOrShortNames("7z", workspace);
    }

    private static void preservesOrdinaryNamesThatResembleWindowsDevicesOrShortNames(String format, Path workspace) throws IOException {
        Path archive = archive(workspace, format, file("COM0.txt", "zero"), file("COM10.txt", "ten"),
                file("NUL-data.txt", "ordinary"), file("nested~1/keep.txt", "tilde"));

        try (BaseArchive base = BaseArchive.open(workspace, archive)) {
            assertEquals(Map.of("COM0.txt", 4L, "COM10.txt", 3L, "NUL-data.txt", 8L,
                    "nested~1/keep.txt", 5L), Tree.list(base.root()));
            assertEquals("tilde", Files.readString(base.root().resolve("nested~1/keep.txt")));
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void preservesNamesThatOnlyHaveSpecialMeaningOnWindowsForZip(@TempDir Path workspace) throws IOException {
        preservesNamesThatOnlyHaveSpecialMeaningOnWindows("zip", workspace);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void preservesNamesThatOnlyHaveSpecialMeaningOnWindowsForSevenZ(@TempDir Path workspace) throws IOException {
        preservesNamesThatOnlyHaveSpecialMeaningOnWindows("7z", workspace);
    }

    private static void preservesNamesThatOnlyHaveSpecialMeaningOnWindows(String format, Path workspace) throws IOException {
        Path archive = archive(workspace, format, file("NUL.txt", "ordinary"), file("folder./keep.txt", "dot"));

        try (BaseArchive base = BaseArchive.open(workspace, archive)) {
            assertEquals(Map.of("NUL.txt", 8L, "folder./keep.txt", 3L), Tree.list(base.root()));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsShortDirectoryAliasesIntoEarlierArchiveEntriesForZip(@TempDir Path workspace) throws IOException {
        rejectsShortDirectoryAliasesIntoEarlierArchiveEntries("zip", workspace);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsShortDirectoryAliasesIntoEarlierArchiveEntriesForSevenZ(@TempDir Path workspace) throws IOException {
        rejectsShortDirectoryAliasesIntoEarlierArchiveEntries("7z", workspace);
    }

    private static void rejectsShortDirectoryAliasesIntoEarlierArchiveEntries(String format, Path workspace) throws IOException {
        Path longName = Files.createDirectory(workspace.resolve("LongDirectoryName"));
        Path shortName = workspace.resolve("LONGDI~1");
        assumeTrue(Files.isDirectory(shortName) && Files.isSameFile(longName, shortName),
                "8.3 short names must be enabled on this volume");
        Path archive = archive(workspace, format, file("LongDirectoryName/first.txt", "first"),
                file("LONGDI~1/second.txt", "must not merge"));

        rejectsAndCleans(workspace, archive, "filesystem link or alias");
    }

    private static Stream<Arguments> collisions() {
        return Stream.of("zip", "7z").flatMap(format -> Stream.of(
                Arguments.of(format, "a", "a/child", "file-directory collision"),
                Arguments.of(format, "a/child", "a", "file-directory collision"),
                Arguments.of(format, "a/", "a", "duplicate archive entry"),
                Arguments.of(format, "a", "a/", "duplicate archive entry")));
    }

    @ParameterizedTest(name = "{0}: {1} then {2}")
    @MethodSource("collisions")
    void rejectsFilesThatCollideWithDirectories(String format, String first, String second, String reason,
            @TempDir Path workspace) throws IOException {
        Path archive = archive(workspace, format, file(first, ""), file(second, ""));
        rejectsAndCleans(workspace, archive, reason);
    }

    @Test
    void extractsUtf8ZipNames(@TempDir Path workspace) throws IOException {
        Path archive = archive(workspace, "zip", file("模组/中文.txt", "content"));

        try (BaseArchive base = BaseArchive.open(workspace, archive)) {
            assertEquals("content", Files.readString(base.root().resolve("模组/中文.txt")));
        }
    }

    @Test
    void rejectsLegacyEncodedZipNamesInsteadOfPublishingReplacementCharacters(@TempDir Path workspace)
            throws IOException {
        Path archive = legacyNameZip(workspace);

        rejectsAndCleans(workspace, archive, "not UTF-8");
    }

    @Test
    void rejectsAnUndecodableUnicodePathFieldInsteadOfPublishingReplacementCharacters(
            @TempDir Path workspace) throws IOException {
        Path archive = workspace.resolve("base.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(archive.toFile())) {
            out.setUseLanguageEncodingFlag(false);
            ZipArchiveEntry entry = new ZipArchiveEntry("AB/mods.txt");
            entry.setTimeLocal(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
            entry.addExtraField(unicodePath("AB/mods.txt", new byte[] {(byte) 0xD6, (byte) 0xD0}));
            out.putArchiveEntry(entry);
            out.write(TestFiles.bytes("content"));
            out.closeArchiveEntry();
        }

        rejectsAndCleans(workspace, archive, "not UTF-8");
    }

    @Test
    void acceptsALegacyRawNameWhenTheUnicodePathFieldDecodes(@TempDir Path workspace) throws IOException {
        String name = "配置/模组.txt";
        Path archive = workspace.resolve("base.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(archive.toFile())) {
            out.setEncoding("GBK");
            ZipArchiveEntry entry = new ZipArchiveEntry(name);
            entry.setTimeLocal(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
            entry.addExtraField(unicodePath(name.getBytes(Charset.forName("GBK")),
                    name.getBytes(StandardCharsets.UTF_8)));
            out.putArchiveEntry(entry);
            out.write(TestFiles.bytes("content"));
            out.closeArchiveEntry();
        }

        try (BaseArchive base = BaseArchive.open(workspace, archive)) {
            assertEquals("content", Files.readString(base.root().resolve(name)));
        }
    }

    private static UnicodePathExtraField unicodePath(byte[] rawName, byte[] unicodeName) {
        CRC32 crc = new CRC32();
        crc.update(rawName);
        UnicodePathExtraField field = new UnicodePathExtraField();
        field.setNameCRC32(crc.getValue());
        field.setUnicodeName(unicodeName);
        return field;
    }

    private static UnicodePathExtraField unicodePath(String rawName, byte[] unicodeName) {
        return unicodePath(rawName.getBytes(StandardCharsets.US_ASCII), unicodeName);
    }

    @Test
    void rejectsDuplicateZipNamesForFileEntries(@TempDir Path workspace) throws IOException {
        rejectsDuplicateZipNames("a", workspace);
    }

    @Test
    void rejectsDuplicateZipNamesForDirectoryEntries(@TempDir Path workspace) throws IOException {
        rejectsDuplicateZipNames("a/", workspace);
    }

    private static void rejectsDuplicateZipNames(String name, Path workspace) throws IOException {
        Path archive = archive(workspace, "zip", file(name, ""), file(name, ""));
        rejectsAndCleans(workspace, archive, "duplicate archive entry");
    }

    @Test
    void rejectsDuplicateSevenZNamesForFileEntries(@TempDir Path workspace) throws IOException {
        rejectsDuplicateSevenZNames("a", workspace);
    }

    @Test
    void rejectsDuplicateSevenZNamesForDirectoryEntries(@TempDir Path workspace) throws IOException {
        rejectsDuplicateSevenZNames("a/", workspace);
    }

    private static void rejectsDuplicateSevenZNames(String name, Path workspace) throws IOException {
        Path archive = archive(workspace, "7z", file(name, ""), file(name, ""));
        rejectsAndCleans(workspace, archive, "duplicate archive entry");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(ints = {UnixStat.LINK_FLAG, 0010000, 0020000, 0060000, 0140000})
    void rejectsZipLinksAndSpecialFileModes(int mode, @TempDir Path workspace) throws IOException {
        ZipArchiveEntry entry = new ZipArchiveEntry("special");
        entry.setUnixMode(mode | 0644);
        rejectsAndCleans(workspace, zip(workspace, entry), "link or special");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(ints = {0x400, 0x40, 0xA0008000, 0x10008000, 0x20008000})
    void rejectsSevenZLinksAndSpecialFileAttributes(int attributes, @TempDir Path workspace) throws IOException {
        Path archive = sevenZ(workspace, entry -> {
            entry.setHasWindowsAttributes(true);
            entry.setWindowsAttributes(attributes);
        });
        rejectsAndCleans(workspace, archive, "link or special");
    }

    @Test
    void rejectsSevenZAntiItems(@TempDir Path workspace) throws IOException {
        Path archive = sevenZ(workspace, entry -> entry.setAntiItem(true));
        rejectsAndCleans(workspace, archive, "anti-item");
    }

    @Test
    void rejectsCorruptArchivesAndRemovesTheirStagingForZip(@TempDir Path workspace) throws IOException {
        rejectsCorruptArchivesAndRemovesTheirStaging("zip", workspace);
    }

    @Test
    void rejectsCorruptArchivesAndRemovesTheirStagingForSevenZ(@TempDir Path workspace) throws IOException {
        rejectsCorruptArchivesAndRemovesTheirStaging("7z", workspace);
    }

    private static void rejectsCorruptArchivesAndRemovesTheirStaging(String format, Path workspace) throws IOException {
        Path archive = workspace.resolve("base." + format);
        Files.writeString(archive, "not an archive");
        rejectsAndCleans(workspace, archive, "cannot extract base archive");
    }

    @Test
    void rejectsStoredZipContentWithAnIncorrectChecksum(@TempDir Path workspace) throws IOException {
        Path archive = workspace.resolve("base.zip");
        byte[] content = TestFiles.bytes("original");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            ZipEntry entry = new ZipEntry("a");
            entry.setTimeLocal(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(content.length);
            CRC32 crc = new CRC32();
            crc.update(content);
            entry.setCrc(crc.getValue());
            out.putNextEntry(entry);
            out.write(content);
            out.closeEntry();
        }
        byte[] bytes = Files.readAllBytes(archive);
        bytes[31] ^= 1; // Local file header is 30 bytes, followed by the one-byte name.
        Files.write(archive, bytes);
        rejectsAndCleans(workspace, archive, "checksum mismatch");
    }

    @Test
    void rejectsZipContentWithAnIncorrectDeclaredSize(@TempDir Path workspace) throws IOException {
        Path archive = archive(workspace, "zip", file("a", "content"));
        byte[] bytes = Files.readAllBytes(archive);
        int central = indexOf(bytes, new byte[] {0x50, 0x4b, 0x01, 0x02}, 0);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(central + 24, 8);
        Files.write(archive, bytes);
        rejectsAndCleans(workspace, archive, "size or checksum mismatch");
    }

    @Test
    void rejectsEncryptedZipEntries(@TempDir Path workspace) throws IOException {
        Path archive = archive(workspace, "zip", file("a", "content"));
        byte[] bytes = Files.readAllBytes(archive);
        int central = indexOf(bytes, new byte[] {0x50, 0x4b, 0x01, 0x02}, 0);
        bytes[6] |= 1;
        bytes[central + 8] |= 1;
        Files.write(archive, bytes);
        rejectsAndCleans(workspace, archive, "encrypted or unreadable");
    }

    @Test
    void rejectsUnreadableZipCompression(@TempDir Path workspace) throws IOException {
        Path archive = archive(workspace, "zip", file("a", "content"));
        byte[] bytes = Files.readAllBytes(archive);
        int central = indexOf(bytes, new byte[] {0x50, 0x4b, 0x01, 0x02}, 0);
        bytes[8] = 99;
        bytes[central + 10] = 99;
        Files.write(archive, bytes);
        rejectsAndCleans(workspace, archive, "encrypted or unreadable");
    }

    private static int indexOf(byte[] bytes, byte[] pattern, int start) {
        for (int i = start; i <= bytes.length - pattern.length; i++) {
            if (Arrays.equals(bytes, i, i + pattern.length, pattern, 0, pattern.length)) {
                return i;
            }
        }
        throw new AssertionError("fixture marker not found: " + Arrays.toString(pattern));
    }

    private static void changeSevenZCoder(Path archive, byte[] coder) throws IOException {
        byte[] bytes = Files.readAllBytes(archive);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int start = Math.toIntExact(32 + header.getLong(12));
        int offset = indexOf(bytes, new byte[] {0x0b, 1, 0, 1, 0x21, 0x21, 1}, start) + 4;
        byte[] changed = new byte[bytes.length + coder.length - 4];
        System.arraycopy(bytes, 0, changed, 0, offset);
        System.arraycopy(coder, 0, changed, offset, coder.length);
        System.arraycopy(bytes, offset + 4, changed, offset + coder.length, bytes.length - offset - 4);
        ByteBuffer updated = ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN);
        updated.putLong(20, changed.length - start);
        CRC32 crc = new CRC32();
        crc.update(changed, start, changed.length - start);
        updated.putInt(28, (int) crc.getValue());
        crc.reset();
        crc.update(changed, 12, 20);
        updated.putInt(8, (int) crc.getValue());
        Files.write(archive, changed);
    }

    @Test
    void rejectsSevenZDictionariesBeyondTheDecoderMemoryBudget(@TempDir Path workspace) throws IOException {
        Path archive = archive(workspace, "7z", file("a", "content"));
        // LZMA2 property 34 declares a 512 MiB dictionary; the payload remains tiny.
        changeSevenZCoder(archive, new byte[] {0x21, 0x21, 1, 34});
        rejectsAndCleans(workspace, archive, "memory");
    }

    @Test
    void rejectsEncryptedSevenZEntries(@TempDir Path workspace) throws IOException {
        Path archive = archive(workspace, "7z", file("a", "content"));
        // Replace the real writer's LZMA2 coder with AES256SHA256, without adding a password.
        changeSevenZCoder(archive, new byte[] {0x24, 0x06, (byte) 0xf1, 0x07, 0x01, 1, 0});
        rejectsAndCleans(workspace, archive, "encrypted");
    }

    @Test
    void rejectsUnreadableSevenZCompression(@TempDir Path workspace) throws IOException {
        Path archive = archive(workspace, "7z", file("a", "content"));
        changeSevenZCoder(archive, new byte[] {0x21, 0x7f, 1, 0});
        rejectsAndCleans(workspace, archive, "compression method");
    }

    @Test
    void rejectsRedirectedWorkspaceTemporaryStorage(@TempDir Path root) throws IOException {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path archive = archive(workspace, "zip", file("a", "content"));
        Files.createDirectories(Layout.stateDir(workspace));
        try {
            Files.createSymbolicLink(workspace.resolve(Layout.STATE_DIR).resolve(Layout.TMP), outside);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "symbolic links unavailable on this platform: " + e.getMessage());
        }
        UserError error = assertThrows(UserError.class, () -> {
            try (BaseArchive ignored = BaseArchive.open(workspace, archive)) {
                // Release resources even if the redirection is mistakenly accepted.
            }
        });
        assertTrue(error.getMessage().contains(archive.toString()), error.getMessage());
        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        try (var remaining = Files.list(outside)) {
            assertEquals(0, remaining.count());
        }
    }
}

package io.github.deshark42.modpackupdater.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.TestLogs;
import io.github.deshark42.modpackupdater.TestSigning;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Chunker;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.model.ChunkTable;
import io.github.deshark42.modpackupdater.model.Chunking;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import io.github.deshark42.modpackupdater.model.Pointer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UpdateTest {

    private static final String TAG = "client";

    private TestLogs captured = new TestLogs();

    private void resetLog() {
        captured.close();
        captured = new TestLogs();
    }

    /** Runs an update the way the CLI does: console capture, and a transcript. */
    private void update(Path workspace, String tag, String releaseId) {
        resetLog();
        captured.begin(workspace, "update-" + tag);
        try {
            Update.run(workspace, tag, releaseId);
        } finally {
            captured.close();
        }
    }

    /** The message body of a log line: what follows the timestamp and the level. */
    private static String body(String line) {
        String[] parts = line.trim().split("\\s+", 4);
        return parts.length < 4 ? "" : parts[3];
    }

    private static List<String> stageStarts(String transcript) {
        String prefix = "tag " + TAG + ": ";
        return transcript.lines().filter(line -> line.contains(" DEBUG ")).map(UpdateTest::body)
                .filter(message -> message.startsWith(prefix))
                .map(message -> message.substring(prefix.length())).toList();
    }

    /** Whether this is the transcript line that reports one added, replaced or deleted path. */
    private static boolean isItemLine(String line) {
        String message = body(line);
        return message.startsWith("added ") || message.startsWith("replaced ")
                || message.startsWith("deleted ");
    }

    /** Replaces the tag's base tree with the given files, then publishes it, the way a publisher works. */
    private void publish(Path publisher, String tag, String releaseId, Map<String, byte[]> files)
            throws IOException {
        Path base = Layout.baseDir(publisher, tag);
        if (Files.isDirectory(base)) {
            try (var walk = Files.walk(base)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            TestFiles.write(base, file.getKey(), file.getValue());
        }
        Publish.run(publisher, tag, releaseId);
    }

    private static String applied(Path workspace, String tag) {
        return Pointer.read(Layout.currentFile(workspace, tag)).releaseId();
    }

    private static String releaseKey(String tag, String releaseId) {
        return TestWorkspace.bucketPath(ObjectKeys.release(tag, releaseId));
    }

    private static String latestKey(String tag) {
        return TestWorkspace.bucketPath(ObjectKeys.latest(tag));
    }

    private static String signatureKey(String tag, String releaseId) {
        return TestWorkspace.bucketPath(ObjectKeys.signature(tag, releaseId));
    }

    private static String chunkKey(String hash) {
        return TestWorkspace.bucketPath(ObjectKeys.blob(hash));
    }

    private static String indexKey(String id) {
        return TestWorkspace.bucketPath(ObjectKeys.index(id));
    }

    private static Manifest manifest(FakeS3 s3, String tag, String releaseId) {
        return Manifest.parse(s3.text(releaseKey(tag, releaseId)), "release " + releaseId);
    }

    /**
     * The bytes a run has to fetch besides chunks: the latest pointer, the target manifest, and one
     * request per distinct chunk table of the paths whose content it has to assemble.
     */
    private static long metadataBytes(FakeS3 s3, String tag, String releaseId, List<String> paths) {
        Manifest manifest = manifest(s3, tag, releaseId);
        long bytes = s3.object(latestKey(tag)).length + s3.object(releaseKey(tag, releaseId)).length
                + s3.object(signatureKey(tag, releaseId)).length;
        Set<String> tables = new HashSet<>();
        for (String path : paths) {
            tables.add(manifest.files().get(path).indexId());
        }
        for (String table : tables) {
            bytes += s3.object(indexKey(table)).length;
        }
        return bytes;
    }

    private static List<Chunker.Cut> cuts(Path dir, byte[] content) throws IOException {
        Path file = Files.createTempFile(dir, "sample-", ".bin");
        try {
            Files.write(file, content);
            List<Chunker.Cut> cuts = new ArrayList<>();
            Chunker.split(file, Chunking.DEFAULT, cuts::add);
            return cuts;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static Set<String> hashes(List<Chunker.Cut> cuts) {
        Set<String> hashes = new HashSet<>();
        for (Chunker.Cut cut : cuts) {
            hashes.add(cut.hash());
        }
        return hashes;
    }

    private static List<String> newHashes(List<Chunker.Cut> cuts, Set<String> have) {
        List<String> missing = new ArrayList<>();
        for (Chunker.Cut cut : cuts) {
            if (!have.contains(cut.hash())) {
                missing.add(cut.hash());
            }
        }
        return missing;
    }

    private static long bytesOf(List<Chunker.Cut> cuts, List<String> hashes) {
        Set<String> wanted = new HashSet<>(hashes);
        long bytes = 0;
        for (Chunker.Cut cut : cuts) {
            if (wanted.contains(cut.hash())) {
                bytes += cut.length();
            }
        }
        return bytes;
    }

    /** The number of chunks the transcript says were reused; a run that reused none prints no line. */
    private static int reportedReuse(Path workspace) throws IOException {
        var matcher = java.util.regex.Pattern.compile("reused (\\d+) chunks?").matcher(transcript(workspace));
        int reused = 0;
        while (matcher.find()) {
            reused = Integer.parseInt(matcher.group(1));
        }
        return reused;
    }

    private static long getCount(FakeS3 s3, String key) {
        return s3.requests().stream().filter(request -> request.equals("GET " + key)).count();
    }

    /** The run's staging directory holds no chunk any more: the failed run cleaned up after itself. */
    private static void assertNothingStaged(Path workspace) throws IOException {
        Path temp = Layout.tempDir(workspace);
        if (Files.exists(temp)) {
            try (var walk = Files.walk(temp)) {
                assertEquals(List.of(), walk.filter(Files::isRegularFile).toList(), "nothing is staged");
            }
        }
    }

    private static List<Path> logs(Path workspace) throws IOException {
        try (var files = Files.list(Layout.logsDir(workspace))) {
            return files.toList();
        }
    }

    /** Everything the run wrote to its transcript, which keeps the per-file and per-chunk detail. */
    private static String transcript(Path workspace) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Path file : logs(workspace)) {
            text.append(Files.readString(file, StandardCharsets.UTF_8));
        }
        return text.toString();
    }

    /** The chunk table the publisher recorded for one file, next to the cuts of its content. */
    private record ChunkTableSplit(ChunkTable table, List<Chunker.Cut> cuts) {
    }

    private static ChunkTableSplit split(Path publisher, String tag, String relative) throws IOException {
        List<Chunker.Cut> cuts = new ArrayList<>();
        ChunkTable table = Chunker.split(Layout.baseDir(publisher, tag).resolve(relative), Chunking.DEFAULT,
                cuts::add).table();
        return new ChunkTableSplit(table, cuts);
    }

    /** Windows junctions need no symbolic-link privilege; both are forbidden below a workspace. */
    private static void directoryLink(Path link, Path target) throws Exception {
        Files.createDirectories(link.getParent());
        if (System.getProperty("os.name").startsWith("Windows")) {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "junction creation timed out");
                assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8));
            } finally {
                process.destroyForcibly();
            }
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    @ParameterizedTest(name = "a linked directory cannot be used to {0} a payload file")
    @ValueSource(strings = {"add", "replace", "delete"})
    void refusesLinkedPayloadDirectoriesBeforeApplyingAnyPath(String action, @TempDir Path root) throws Exception {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            Map<String, byte[]> before = new HashMap<>();
            before.put("config/options.cfg", TestFiles.bytes("old config"));
            if (!action.equals("add")) {
                before.put("mods/a.jar", TestFiles.bytes("old mod"));
            }
            publish(publisher, TAG, "2.9.0", before);
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, null);
            Path outside = root.resolve("outside");
            if (Files.exists(player.resolve("mods"))) {
                Files.move(player.resolve("mods"), outside);
            } else {
                TestFiles.write(outside, "a.jar", "old mod");
            }
            directoryLink(player.resolve("mods"), outside);
            Map<String, byte[]> after = new HashMap<>();
            after.put("config/options.cfg", TestFiles.bytes("new config"));
            if (!action.equals("delete")) {
                after.put("mods/a.jar", TestFiles.bytes("new mod"));
            }
            publish(publisher, TAG, "2.9.1", after);
            int requests = s3.requests().size();

            UserError error = assertThrows(UserError.class, () -> update(player, TAG, null));

            assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
            assertEquals("old mod", Files.readString(outside.resolve("a.jar")));
            assertEquals("old config", Files.readString(player.resolve("config/options.cfg")),
                    "a safe path earlier in the plan is not applied before the unsafe path is found");
            assertEquals("2.9.0", applied(player, TAG));
            assertFalse(Files.exists(Layout.backupsDir(player, TAG)));
            assertFalse(Files.exists(Layout.releaseFile(player, TAG, "2.9.1")));
            List<String> expected = action.equals("add")
                    ? List.of("GET " + latestKey(TAG), "GET " + releaseKey(TAG, "2.9.1"),
                            "GET " + signatureKey(TAG, "2.9.1"))
                    : List.of("GET " + latestKey(TAG));
            assertEquals(expected, s3.requests().subList(requests, s3.requests().size()));
            assertNothingStaged(player);
        }
    }

    @ParameterizedTest(name = "state directory {0} cannot redirect update IO")
    @ValueSource(strings = {"logs", "tmp", "tmp/blob", "tmp/file", "tmp/blob/nested",
            "client/releases", "client/backup/.initial", "client/backup/.initial/mods"})
    void refusesLinkedStateDirectoriesWithoutChangingExternalFiles(String relative, @TempDir Path root)
            throws Exception {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", TestFiles.bytes("new mod")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Config.fillMissing(player);
            String config = Files.readString(Layout.configFile(player));
            Path outside = root.resolve("outside");
            TestFiles.write(outside, "sentinel", "external");
            directoryLink(player.resolve(Layout.STATE_DIR).resolve(relative), outside);
            int requests = s3.requests().size();

            UserError error = assertThrows(UserError.class, () -> update(player, TAG, null));

            assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
            assertEquals("external", Files.readString(outside.resolve("sentinel")));
            try (var entries = Files.list(outside)) {
                assertEquals(List.of("sentinel"), entries.map(path -> path.getFileName().toString()).toList());
            }
            assertEquals(config, Files.readString(Layout.configFile(player)));
            assertFalse(Files.exists(player.resolve("mods/a.jar")));
            assertFalse(Files.exists(player.resolve(Layout.STATE_DIR).resolve(TAG).resolve(Layout.CURRENT)));
            assertTrue(s3.requests().subList(requests, s3.requests().size()).stream()
                    .noneMatch(request -> request.startsWith("GET " + TestWorkspace.bucketPath(ObjectKeys.BLOB + "/"))),
                    "unsafe state is rejected before chunks are downloaded");
        }
    }

    @Test
    void refusesALinkedStateRootBeforeOpeningALog(@TempDir Path root) throws Exception {
        try (FakeS3 s3 = new FakeS3()) {
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Path state = Layout.stateDir(player);
            Path outside = root.resolve("outside");
            Files.move(state, outside);
            String config = Files.readString(outside.resolve(Layout.CONFIG));
            directoryLink(state, outside);

            UserError error = assertThrows(UserError.class, () -> update(player, TAG, null));

            assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
            assertEquals(config, Files.readString(outside.resolve(Layout.CONFIG)));
            assertFalse(Files.exists(outside.resolve(Layout.LOGS)));
            assertFalse(Files.exists(outside.resolve(TAG)));
            assertEquals(List.of(), s3.requests());
        }
    }

    @Test
    void refusesLinksInsideKeptBackupsBeforeReusingTheirContents(@TempDir Path root) throws Exception {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", TestFiles.bytes("old mod")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, null);
            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", TestFiles.bytes("new mod")));
            Path outside = root.resolve("outside");
            TestFiles.write(outside, "a.jar", "external");
            directoryLink(Layout.backupDir(player, TAG, "2.9.1").resolve("mods"), outside);
            int requests = s3.requests().size();

            UserError error = assertThrows(UserError.class, () -> update(player, TAG, null));

            assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
            assertEquals("external", Files.readString(outside.resolve("a.jar")));
            assertEquals("old mod", Files.readString(player.resolve("mods/a.jar")));
            assertEquals("2.9.0", applied(player, TAG));
            assertFalse(Files.exists(Layout.backupDir(player, TAG, "2.9.0")));
            assertTrue(s3.requests().subList(requests, s3.requests().size()).stream()
                    .noneMatch(request -> request.startsWith("GET " + TestWorkspace.bucketPath(ObjectKeys.BLOB + "/"))));
            assertNothingStaged(player);
        }
    }

    @Test
    void replacesAnOrdinaryFileWithADirectoryOfTrackedFiles(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods", TestFiles.bytes("old file")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, null);
            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", TestFiles.bytes("new mod")));

            update(player, TAG, null);

            assertEquals("new mod", Files.readString(player.resolve("mods/a.jar")));
            assertEquals("old file", Files.readString(Layout.backupDir(player, TAG, "2.9.0").resolve("mods")));
            assertEquals("2.9.1", applied(player, TAG));
        }
    }

    @Test
    void refusesALinkedTranscriptBeforeAppendingToAnExternalFile(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3(); TestLogs logs = new TestLogs()) {
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Path outside = root.resolve("external.log");
            Files.writeString(outside, "external");
            Clock clock = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC);
            Path directory = Layout.logsDir(player);
            Files.createDirectories(directory);
            Path transcript = directory.resolve("update-" + TAG + "-" + LocalDate.now(clock) + ".log");
            try {
                Files.createSymbolicLink(transcript, outside);
            } catch (UnsupportedOperationException | IOException e) {
                assumeTrue(false, "file symbolic links require filesystem support and Windows privileges: " + e);
            }

            UserError error = assertThrows(UserError.class, () -> {
                logs.begin(player, "update-" + TAG, clock);
                Update.run(player, TAG, null);
            });

            assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
            assertEquals("external", Files.readString(outside));
            assertFalse(Files.exists(Layout.currentFile(player, TAG)));
            assertEquals(List.of(), s3.requests());
        }
    }

    @Test
    void refusesALinkedConfigFileBeforeReadingOrFillingDefaults(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Path config = Layout.configFile(player);
            Path outside = root.resolve("external-config.json");
            Files.move(config, outside);
            String before = Files.readString(outside);
            try {
                Files.createSymbolicLink(config, outside);
            } catch (UnsupportedOperationException | IOException e) {
                assumeTrue(false, "file symbolic links require filesystem support and Windows privileges: " + e);
            }

            UserError error = assertThrows(UserError.class, () -> update(player, TAG, null));

            assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
            assertEquals(before, Files.readString(outside));
            assertFalse(Files.exists(Layout.currentFile(player, TAG)));
            assertEquals(List.of(), s3.requests());
        }
    }

    @ParameterizedTest(name = "release cannot replace local trust file {0}")
    @ValueSource(strings = {Layout.CONFIG, "keys/release-public.pem", "keys/release-private.pem"})
    void refusesARemoteManifestThatReplacesLocalTrustConfiguration(String trustFile, @TempDir Path root)
            throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", TestFiles.bytes("untrusted config")));
            // Even an authorized signer must not be able to replace the updater's own state.
            byte[] malicious = TestFiles.bytes(s3.text(releaseKey(TAG, "2.9.0"))
                    .replace("mods/a.jar", Layout.STATE_DIR + "/" + trustFile));
            s3.put(releaseKey(TAG, "2.9.0"), malicious);
            s3.put(signatureKey(TAG, "2.9.0"), TestSigning.sign(malicious));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Config.fillMissing(player);
            String config = Files.readString(Layout.configFile(player));
            Path protectedFile = Layout.stateDir(player).resolve(trustFile);
            byte[] trustBytes = Files.readAllBytes(protectedFile);
            int before = s3.requests().size();

            UserError error = assertThrows(UserError.class, () -> update(player, TAG, null));

            assertTrue(error.getMessage().contains("reserved state directory"), error.getMessage());
            assertEquals(config, Files.readString(Layout.configFile(player)));
            assertArrayEquals(trustBytes, Files.readAllBytes(protectedFile));
            assertFalse(Files.exists(Layout.currentFile(player, TAG)));
            assertFalse(Files.exists(Layout.releasesDir(player, TAG)));
            assertFalse(Files.exists(Layout.backupsDir(player, TAG)));
            assertEquals(List.of("GET " + latestKey(TAG), "GET " + releaseKey(TAG, "2.9.0"),
                            "GET " + signatureKey(TAG, "2.9.0")),
                    s3.requests().subList(before, s3.requests().size()));
            assertNothingStaged(player);
        }
    }

    @Test
    void refusesReservedPathsInTheOldLocalManifestBeforeDeletingAnything(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", TestFiles.bytes("A")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, null);
            Path old = Layout.releaseFile(player, TAG, "2.9.0");
            Files.writeString(old, Files.readString(old).replace("mods/a.jar", Layout.STATE_DIR + "/" + Layout.CONFIG));
            publish(publisher, TAG, "2.9.1", Map.of("mods/b.jar", TestFiles.bytes("B")));
            String config = Files.readString(Layout.configFile(player));
            int before = s3.requests().size();

            UserError error = assertThrows(UserError.class, () -> update(player, TAG, null));

            assertTrue(error.getMessage().contains("reserved state directory"), error.getMessage());
            assertEquals(config, Files.readString(Layout.configFile(player)));
            assertEquals("A", Files.readString(player.resolve("mods/a.jar")));
            assertFalse(Files.exists(player.resolve("mods/b.jar")));
            assertFalse(Files.exists(Layout.backupsDir(player, TAG)));
            assertEquals("2.9.0", applied(player, TAG));
            assertEquals(List.of("GET " + latestKey(TAG)), s3.requests().subList(before, s3.requests().size()));
            assertNothingStaged(player);
        }
    }

    @Test
    void refusesASignedPayloadClaimingTheUpdaterEvenWhenItsBytesMatch(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            String runner = "tools/custom runner.bin";
            publish(publisher, TAG, "2.9.0", Map.of("a-first.cfg", TestFiles.bytes("new"),
                    runner, TestFiles.bytes("updater")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Config.fillMissing(player);
            TestFiles.write(player, "a-first.cfg", "local");
            TestFiles.write(player, runner, "updater");
            String config = Files.readString(Layout.configFile(player));
            byte[] publicKey = Files.readAllBytes(Config.load(player).publicKeyFile(player));
            int before = s3.requests().size();

            UserError error = assertThrows(UserError.class,
                    () -> Update.run(player, TAG, null, player.resolve(runner)));

            assertTrue(error.getMessage().contains("running updater"), error.getMessage());
            assertEquals("updater", Files.readString(player.resolve(runner)));
            assertEquals("local", Files.readString(player.resolve("a-first.cfg")));
            assertEquals(config, Files.readString(Layout.configFile(player)));
            assertArrayEquals(publicKey, Files.readAllBytes(Config.load(player).publicKeyFile(player)));
            assertFalse(Files.exists(Layout.currentFile(player, TAG)));
            assertFalse(Files.exists(Layout.releasesDir(player, TAG)));
            assertFalse(Files.exists(Layout.backupsDir(player, TAG)));
            assertEquals(List.of("GET " + latestKey(TAG), "GET " + releaseKey(TAG, "2.9.0"),
                            "GET " + signatureKey(TAG, "2.9.0")),
                    s3.requests().subList(before, s3.requests().size()));
            assertNothingStaged(player);
        }
    }

    @ParameterizedTest(name = "legacy updater ownership is refused for {0}")
    @ValueSource(strings = {"deleted", "unchanged"})
    void refusesAnOldManifestClaimingTheUpdatersParentBeforeAnyMutation(String scenario, @TempDir Path root)
            throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("a-first.cfg", TestFiles.bytes("A"),
                    "tools", TestFiles.bytes("old file")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, null);
            // A legacy tracked file has since become the directory containing the locally installed updater.
            Files.delete(player.resolve("tools"));
            TestFiles.write(player, "tools/runner.jar", "updater");
            Map<String, byte[]> next = new HashMap<>();
            next.put("a-first.cfg", TestFiles.bytes("new A"));
            if (scenario.equals("unchanged")) {
                next.put("tools", TestFiles.bytes("old file"));
            }
            publish(publisher, TAG, "2.9.1", next);
            Path oldFile = Layout.releaseFile(player, TAG, "2.9.0");
            // A large legacy record exercises parallel preflight.
            Manifest legacy = Manifest.read(oldFile);
            var tracked = new HashMap<>(legacy.files());
            for (int i = 0; i < 256; i++) {
                tracked.put("padding/missing-" + i, legacy.files().get("a-first.cfg"));
            }
            new Manifest(legacy.tag(), legacy.releaseId(), legacy.publishedAt(), legacy.hashAlgorithm(), tracked)
                    .write(oldFile);
            String oldManifest = Files.readString(oldFile);
            int before = s3.requests().size();

            UserError error = assertThrows(UserError.class,
                    () -> Update.run(player, TAG, null, player.resolve("tools/runner.jar")));

            assertTrue(error.getMessage().contains("running updater"), error.getMessage());
            assertEquals("updater", Files.readString(player.resolve("tools/runner.jar")));
            assertEquals("A", Files.readString(player.resolve("a-first.cfg")));
            assertEquals(oldManifest, Files.readString(oldFile));
            assertEquals("2.9.0", applied(player, TAG));
            assertFalse(Files.exists(Layout.releaseFile(player, TAG, "2.9.1")));
            assertFalse(Files.exists(Layout.backupsDir(player, TAG)));
            assertEquals(List.of("GET " + latestKey(TAG)), s3.requests().subList(before, s3.requests().size()));
            assertNothingStaged(player);
        }
    }

    @Test
    void skipsTheOldManifestPreflightWhenTheLatestReleaseIsAlreadyApplied(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("a-first.cfg", TestFiles.bytes("A"),
                    "tools", TestFiles.bytes("old file")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, null);
            // The legacy tracked file has since become the directory containing the locally installed
            // updater, so the preflight refuses this manifest; a no-op run must not even read it.
            Files.delete(player.resolve("tools"));
            TestFiles.write(player, "tools/runner.jar", "updater");
            Path oldFile = Layout.releaseFile(player, TAG, "2.9.0");
            Manifest legacy = Manifest.read(oldFile);
            var tracked = new HashMap<>(legacy.files());
            for (int i = 0; i < 256; i++) {
                tracked.put("padding/missing-" + i, legacy.files().get("a-first.cfg"));
            }
            new Manifest(legacy.tag(), legacy.releaseId(), legacy.publishedAt(), legacy.hashAlgorithm(), tracked)
                    .write(oldFile);
            String oldManifest = Files.readString(oldFile);
            int before = s3.requests().size();
            int transcriptBefore = transcript(player).length();
            resetLog();

            update(player, TAG, null);

            assertEquals("updater", Files.readString(player.resolve("tools/runner.jar")));
            assertEquals(oldManifest, Files.readString(oldFile), "the run did not touch the old manifest");
            assertEquals("2.9.0", applied(player, TAG));
            assertFalse(Files.exists(Layout.backupsDir(player, TAG)));
            assertEquals(List.of("GET " + latestKey(TAG)), s3.requests().subList(before, s3.requests().size()));
            assertNothingStaged(player);
            assertTrue(captured.outText().contains("tag client is already at release 2.9.0"), captured.outText());
            assertEquals(List.of("loading workspace configuration", "validating signing key paths",
                    "loading trusted public key", "locating updater and workspace", "initializing object store",
                    "resolving latest release", "reading local release pointer"),
                    stageStarts(transcript(player).substring(transcriptBefore)),
                    "a run with nothing to apply does not read the old manifest");
        }
    }

    @Test
    void refusesAnUnprotectedPrivateKeyLocationBeforeFetchingARelease(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            Config.fillMissing(player);
            Path configFile = Layout.configFile(player);
            String config = Files.readString(configFile)
                    .replace(Layout.DEFAULT_PRIVATE_KEY, "mods/future-private.pem");
            Files.writeString(configFile, config);

            UserError error = assertThrows(UserError.class, () -> update(player, TAG, null));

            assertTrue(error.getMessage().contains("must stay under " + Layout.STATE_DIR), error.getMessage());
            assertEquals(config, Files.readString(configFile));
            assertFalse(Files.exists(player.resolve("mods/future-private.pem")));
            assertFalse(Files.exists(Layout.currentFile(player, TAG)));
            assertEquals(List.of(), s3.requests());
            assertNothingStaged(player);
        }
    }

    @Test
    void firstUpdateAssemblesEveryFileFromTheChunksInTheBucket(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] jar = TestFiles.random(300_000, 11);
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of(
                    "mods/a.jar", jar,
                    "mods/b.jar", TestFiles.bytes("L"),
                    "libraries/lwjgl.jar", TestFiles.bytes("L"),
                    "mmc-pack.json", TestFiles.bytes("{}"),
                    "config/empty.cfg", new byte[0]));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            resetLog();

            update(player, TAG, null);

            assertArrayEquals(jar, Files.readAllBytes(player.resolve("mods/a.jar")));
            assertEquals("L", Files.readString(player.resolve("mods/b.jar")));
            assertEquals("L", Files.readString(player.resolve("libraries/lwjgl.jar")));
            assertEquals("{}", Files.readString(player.resolve("mmc-pack.json")));
            assertEquals(0, Files.size(player.resolve("config/empty.cfg")));
            assertEquals("2.9.0", applied(player, TAG));
            assertTrue(Files.isRegularFile(Layout.releaseFile(player, TAG, "2.9.0")));

            List<String> lines = captured.outText().lines().toList();
            String transcript = transcript(player);
            assertEquals(5, transcript.lines().filter(UpdateTest::isItemLine).count(),
                    "one file line per file, none per chunk: " + transcript);
            assertTrue(transcript.contains("downloaded "), transcript);
            assertEquals(0, reportedReuse(player), "a first update has no local copy to reuse");
            assertEquals(List.of(
                    "loading workspace configuration", "validating signing key paths", "loading trusted public key",
                    "locating updater and workspace", "initializing object store", "resolving latest release",
                    "reading local release pointer", "fetching target manifest for release 2.9.0",
                    "fetching target signature for release 2.9.0", "verifying target signature for release 2.9.0",
                    "parsing and validating target manifest for release 2.9.0",
                    "checking 5 target manifest paths for release 2.9.0", "building update plan",
                    "validating affected paths and backups", "checking local target content", "preparing staging directories",
                    "fetching chunk tables", "finding reusable local chunks", "planning missing chunks",
                    "downloading missing chunks", "assembling target files", "applying files and moving backups",
                    "cleaning staging directories", "writing local release state"), stageStarts(transcript));
            assertFalse(captured.outText().contains("tag client:"), "stage details stay out of the console");

            ChunkTableSplit split = split(publisher, TAG, "mods/a.jar");
            assertTrue(split.table().chunks().size() > 1, "the sample is split into several chunks");
            assertEquals(1, getCount(s3, chunkKey(Sha256.ofBytes(TestFiles.bytes("L")))),
                    "a chunk two files share is fetched once: " + s3.requests());
            assertEquals(1, getCount(s3, indexKey(split.table().indexId())));
            String shared = manifest(s3, TAG, "2.9.0").files().get("mods/b.jar").indexId();
            assertEquals(1, getCount(s3, indexKey(shared)),
                    "two files with the same content share one chunk table: " + s3.requests());
            assertEquals(metadataBytes(s3, TAG, "2.9.0", List.of("mods/a.jar", "mods/b.jar",
                    "libraries/lwjgl.jar", "mmc-pack.json", "config/empty.cfg")) + jar.length + 1 + 2,
                    s3.downloadedBytes(), "a first update fetches every distinct chunk once");

            resetLog();
            update(player, TAG, null);
            assertTrue(captured.outText().contains("tag client is already at release 2.9.0"),
                    captured.outText());
        }
    }

    @Test
    void firstUpdateSkipsFilesThatAlreadyMatchTheTarget(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] jar = TestFiles.random(300_000, 71);
            byte[] cfg = TestFiles.bytes("player settings");
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of(
                    "mods/a.jar", jar,
                    "config/x.cfg", cfg,
                    "banned-ips.json", TestFiles.bytes("[]")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            TestFiles.write(player, "mods/a.jar", jar);
            TestFiles.write(player, "config/x.cfg", cfg);
            resetLog();

            update(player, TAG, null);

            assertArrayEquals(jar, Files.readAllBytes(player.resolve("mods/a.jar")));
            assertEquals("[]", Files.readString(player.resolve("banned-ips.json")));
            assertEquals("2.9.0", applied(player, TAG));
            Manifest target = manifest(s3, TAG, "2.9.0");
            assertEquals(0, getCount(s3, indexKey(target.files().get("mods/a.jar").indexId())),
                    "a file already at the target content costs no chunk table: " + s3.requests());
            assertEquals(0, getCount(s3, chunkKey(Sha256.ofBytes(jar))),
                    "a file already at the target content is not downloaded: " + s3.requests());
            assertEquals(metadataBytes(s3, TAG, "2.9.0", List.of("banned-ips.json")) + 2,
                    s3.downloadedBytes(), "only the missing file and the metadata came over the wire");
            assertTrue(captured.outText().contains("skipped 2 files: already at the target content"),
                    captured.outText());
            assertEquals(0, reportedReuse(player));
            assertFalse(Files.exists(Layout.initialBackupDir(player, TAG)),
                    "nothing was in the way, so no first-update backup was made");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"mods/a.jar.", "mods/A.jar", "mods/a.jar.."})
    @EnabledOnOs(OS.WINDOWS)
    void reinstallsMatchingContentWhoseOldSpellingIsDeleted(String alias, @TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] content = TestFiles.bytes("unchanged payload");
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", content));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, "2.9.0");
            Manifest prior = manifest(s3, TAG, "2.9.0");
            // Another publisher on a case-sensitive filesystem can sign these distinct manifest names.
            Manifest target = new Manifest(TAG, "2.9.1", Instant.EPOCH, Manifest.SHA256,
                    Map.of(alias, prior.files().get("mods/a.jar")));
            byte[] bytes = TestFiles.bytes(target.toJson());
            s3.put(releaseKey(TAG, "2.9.1"), bytes);
            s3.put(signatureKey(TAG, "2.9.1"), TestSigning.sign(bytes));
            assertTrue(Files.isSameFile(player.resolve("mods/a.jar"), player.resolve(alias)));
            long chunksBefore = getCount(s3, chunkKey(Sha256.ofBytes(content)));

            update(player, TAG, "2.9.1");

            assertArrayEquals(content, Files.readAllBytes(player.resolve(alias)));
            assertArrayEquals(content, Files.readAllBytes(Layout.backupDir(player, TAG, "2.9.0").resolve("mods/a.jar")));
            assertEquals("2.9.1", applied(player, TAG));
            assertEquals(target.files(), Manifest.read(Layout.releaseFile(player, TAG, "2.9.1")).files());
            assertEquals(chunksBefore, getCount(s3, chunkKey(Sha256.ofBytes(content))),
                    "the matching local payload can supply every chunk before it is moved");
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void leavesTheOldReleaseIntactWhenAnAliasCannotBeStaged(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] content = TestFiles.bytes("unchanged payload");
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", content));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, "2.9.0");
            Manifest prior = manifest(s3, TAG, "2.9.0");
            Manifest target = new Manifest(TAG, "2.9.1", Instant.EPOCH, Manifest.SHA256,
                    Map.of("mods/a.jar.", prior.files().get("mods/a.jar")));
            byte[] bytes = TestFiles.bytes(target.toJson());
            s3.put(releaseKey(TAG, "2.9.1"), bytes);
            s3.put(signatureKey(TAG, "2.9.1"), TestSigning.sign(bytes));
            s3.fail("GET", indexKey(prior.files().get("mods/a.jar").indexId()), 500);
            String oldManifest = Files.readString(Layout.releaseFile(player, TAG, "2.9.0"));
            int before = s3.requests().size();

            UserError error = assertThrows(UserError.class, () -> update(player, TAG, "2.9.1"));

            assertTrue(error.getMessage().contains("500"), error.getMessage());
            assertArrayEquals(content, Files.readAllBytes(player.resolve("mods/a.jar")));
            assertEquals("2.9.0", applied(player, TAG));
            assertEquals(oldManifest, Files.readString(Layout.releaseFile(player, TAG, "2.9.0")));
            assertFalse(Files.exists(Layout.releaseFile(player, TAG, "2.9.1")));
            assertFalse(Files.exists(Layout.backupDir(player, TAG, "2.9.0")));
            assertTrue(s3.requests().subList(before, s3.requests().size()).stream()
                    .noneMatch(request -> request.startsWith("PUT ") || request.contains("/blob/")),
                    "failed staging must not fetch payload chunks or write remote state");
        }
    }

    @Test
    void reinstallsMatchingContentBelowADirectoryThatWillMoveToBackup(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] content = TestFiles.bytes("matching nested target");
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods", TestFiles.bytes("old file")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, "2.9.0");
            Files.delete(player.resolve("mods"));
            TestFiles.write(player, "mods/a.jar", content);
            TestFiles.write(player, "mods/player.cfg", "keep in backup");
            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", content));

            update(player, TAG, "2.9.1");

            assertArrayEquals(content, Files.readAllBytes(player.resolve("mods/a.jar")));
            Path backup = Layout.backupDir(player, TAG, "2.9.0");
            assertArrayEquals(content, Files.readAllBytes(backup.resolve("mods/a.jar")));
            assertEquals("keep in backup", Files.readString(backup.resolve("mods/player.cfg")));
            assertFalse(Files.exists(player.resolve("mods/player.cfg")));
            assertEquals("2.9.1", applied(player, TAG));
        }
    }

    @Test
    void keepsSkippingUnrelatedMatchingContentWhenAnotherPathIsDeleted(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] content = TestFiles.bytes("matching target");
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/old.jar", TestFiles.bytes("old payload")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, "2.9.0");
            publish(publisher, TAG, "2.9.1", Map.of("mods/new.jar", content));
            TestFiles.write(player, "mods/new.jar", content);
            FileTime modified = FileTime.fromMillis(1_600_000_000_000L);
            Files.setLastModifiedTime(player.resolve("mods/new.jar"), modified);
            int before = s3.requests().size();

            update(player, TAG, "2.9.1");

            assertArrayEquals(content, Files.readAllBytes(player.resolve("mods/new.jar")));
            assertEquals(modified, Files.getLastModifiedTime(player.resolve("mods/new.jar")));
            assertEquals("old payload", Files.readString(Layout.backupDir(player, TAG, "2.9.0").resolve("mods/old.jar")));
            assertFalse(Files.exists(player.resolve("mods/old.jar")));
            assertEquals("2.9.1", applied(player, TAG));
            assertEquals(List.of("GET " + releaseKey(TAG, "2.9.1"), "GET " + signatureKey(TAG, "2.9.1")),
                    s3.requests().subList(before, s3.requests().size()));
        }
    }

    @Test
    void skipsMatchingContentWhenADeletedDescendantIsAlreadyBlocked(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] content = TestFiles.bytes("matching target");
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/old.jar", TestFiles.bytes("old payload")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, "2.9.0");
            Files.delete(player.resolve("mods/old.jar"));
            Files.delete(player.resolve("mods"));
            TestFiles.write(player, "mods", "player file blocks old descendant");
            publish(publisher, TAG, "2.9.1", Map.of("config/new.cfg", content));
            TestFiles.write(player, "config/new.cfg", content);
            int before = s3.requests().size();

            update(player, TAG, "2.9.1");

            assertArrayEquals(content, Files.readAllBytes(player.resolve("config/new.cfg")));
            assertEquals("player file blocks old descendant", Files.readString(player.resolve("mods")));
            assertEquals("2.9.1", applied(player, TAG));
            assertFalse(Files.exists(Layout.backupDir(player, TAG, "2.9.0")));
            assertEquals(List.of("GET " + releaseKey(TAG, "2.9.1"), "GET " + signatureKey(TAG, "2.9.1")),
                    s3.requests().subList(before, s3.requests().size()));
        }
    }

    @Test
    void readsAManifestStoredBeforeCompression(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] jar = TestFiles.random(30_000, 73);
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", jar));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            s3.put(releaseKey(TAG, "2.9.0"), TestFiles.bytes(manifest(s3, TAG, "2.9.0").toJson()));
            resetLog();

            update(player, TAG, null);

            assertArrayEquals(jar, Files.readAllBytes(player.resolve("mods/a.jar")));
            assertEquals("2.9.0", applied(player, TAG));
        }
    }

    @Test
    void firstUpdateReusesTheChunksOfAnExistingFilesOtherContent(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] target = TestFiles.random(300_000, 72);
            byte[] existing = target.clone();
            existing[150_000] ^= 0x5a;
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", target));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            TestFiles.write(player, "mods/a.jar", existing);
            resetLog();

            update(player, TAG, null);

            assertArrayEquals(target, Files.readAllBytes(player.resolve("mods/a.jar")));
            List<Chunker.Cut> targetCuts = cuts(root, target);
            List<String> missing = newHashes(targetCuts, hashes(cuts(root, existing)));
            int expectedReuse = targetCuts.size() - missing.size();
            assertTrue(expectedReuse > 0, "most chunks of the existing file are the target content");
            assertEquals(expectedReuse, reportedReuse(player));
            assertEquals(bytesOf(targetCuts, missing)
                    + metadataBytes(s3, TAG, "2.9.0", List.of("mods/a.jar")), s3.downloadedBytes(),
                    "only the chunks the existing file does not have came over the wire");
            assertTrue(s3.downloadedBytes() < target.length / 2, "far less than the file");
            assertArrayEquals(existing, Files.readAllBytes(
                    Layout.initialBackupDir(player, TAG).resolve("mods/a.jar")),
                    "the content that was there moves into the first update backup");
            assertEquals("2.9.0", applied(player, TAG));
        }
    }

    @Test
    void replacesADirectoryAtATrackedPathAndKeepsItInTheBackup(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("config", TestFiles.bytes("now a file")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            TestFiles.write(player, "config/old.cfg", "old");
            resetLog();

            update(player, TAG, null);

            assertEquals("now a file", Files.readString(player.resolve("config")));
            Path kept = Layout.initialBackupDir(player, TAG).resolve("config");
            assertTrue(Files.isDirectory(kept), "the directory that was in the way is kept");
            assertEquals("old", Files.readString(kept.resolve("old.cfg")));
        }
    }

    @Test
    void commitsWithoutTouchingFilesWhoseContentAlreadyMatches(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of(
                    "mods/a.jar", TestFiles.bytes("A"),
                    "mods/b.jar", TestFiles.bytes("B")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, null);

            publish(publisher, TAG, "2.9.1", Map.of(
                    "mods/a.jar", TestFiles.bytes("A2"),
                    "mods/b.jar", TestFiles.bytes("B2")));
            TestFiles.write(player, "mods/a.jar", "A2");
            TestFiles.write(player, "mods/b.jar", "B2");
            FileTime untouched = Files.getLastModifiedTime(player.resolve("mods/a.jar"));
            long already = s3.downloadedBytes();
            resetLog();

            update(player, TAG, null);

            assertEquals("2.9.1", applied(player, TAG));
            assertEquals(untouched, Files.getLastModifiedTime(player.resolve("mods/a.jar")),
                    "a file already at the target content is not rewritten");
            assertEquals(already + metadataBytes(s3, TAG, "2.9.1", List.of()), s3.downloadedBytes(),
                    "no chunk or chunk table is fetched when every target file matches");
            assertTrue(captured.outText().contains("skipped 2 files"), captured.outText());
            assertFalse(Files.exists(Layout.backupDir(player, TAG, "2.9.0")),
                    "nothing is moved aside when nothing is rewritten");
        }
    }

    @Test
    void updateFromThePreviousVersionDownloadsOnlyTheChunksThatChanged(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] before = TestFiles.random(300_000, 21);
            byte[] after = before.clone();
            after[150_000] ^= 0x7f;
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", before));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, null);
            long already = s3.downloadedBytes();

            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", after));
            resetLog();
            update(player, TAG, null);
            long delta = s3.downloadedBytes() - already;

            assertArrayEquals(after, Files.readAllBytes(player.resolve("mods/a.jar")));
            List<Chunker.Cut> oldCuts = cuts(root, before);
            List<Chunker.Cut> newCuts = cuts(root, after);
            List<String> missing = newHashes(newCuts, hashes(oldCuts));
            int expectedReuse = newCuts.size() - missing.size();
            assertTrue(expectedReuse > 0, "most chunks of the file are unchanged");
            assertEquals(expectedReuse, reportedReuse(player));
            assertTrue(captured.outText().contains("reused "), captured.outText());
            assertEquals(bytesOf(newCuts, missing)
                    + metadataBytes(s3, TAG, "2.9.1", List.of("mods/a.jar")), delta,
                    "only the changed chunks and the metadata came over the wire");
            assertTrue(delta < before.length / 2, "far less than the file: " + delta + " of " + before.length);
            assertArrayEquals(before, Files.readAllBytes(
                    Layout.backupDir(player, TAG, "2.9.0").resolve("mods/a.jar")));
            assertEquals("2.9.1", applied(player, TAG));
        }
    }

    @Test
    void reusesChunksThatAnotherFilesBackupCopyAlreadyHas(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] original = TestFiles.random(300_000, 31);
            byte[] changed = original.clone();
            changed[changed.length / 2] ^= 0x55;
            byte[] other = TestFiles.random(30_000, 32);
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", original, "mods/b.jar", other));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, null);
            long already = s3.downloadedBytes();

            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", changed, "mods/b.jar", original));
            resetLog();
            update(player, TAG, null);
            long delta = s3.downloadedBytes() - already;

            assertArrayEquals(changed, Files.readAllBytes(player.resolve("mods/a.jar")));
            assertArrayEquals(original, Files.readAllBytes(player.resolve("mods/b.jar")));
            List<Chunker.Cut> changedCuts = cuts(root, changed);
            List<Chunker.Cut> originalCuts = cuts(root, original);
            Set<String> oldHashes = hashes(cuts(root, original));
            oldHashes.addAll(hashes(cuts(root, other)));
            Set<String> needed = hashes(changedCuts);
            needed.addAll(hashes(originalCuts));
            List<String> missing = new ArrayList<>();
            for (String hash : needed) {
                if (!oldHashes.contains(hash)) {
                    missing.add(hash);
                }
            }
            assertEquals(needed.size() - missing.size(), reportedReuse(player));
            assertEquals(bytesOf(changedCuts, missing)
                    + metadataBytes(s3, TAG, "2.9.1", List.of("mods/a.jar", "mods/b.jar")), delta,
                    "the new b.jar has no chunk of its own to fetch: a.jar's backup copy has them");
            assertTrue(delta < original.length,
                    "b.jar's content was reused, not fetched again: " + delta);
        }
    }

    @Test
    void reusesTheChunksOfTheDeletedCopyWhenAFileOnlyChangedItsName(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] before = TestFiles.random(300_000, 35);
            byte[] after = before.clone();
            after[200_000] ^= 0x21;
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a-1.0.jar", before));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, null);
            long already = s3.downloadedBytes();

            publish(publisher, TAG, "2.9.1", Map.of("mods/a-1.1.jar", after));
            resetLog();
            update(player, TAG, null);
            long delta = s3.downloadedBytes() - already;

            assertArrayEquals(after, Files.readAllBytes(player.resolve("mods/a-1.1.jar")));
            assertFalse(Files.exists(player.resolve("mods/a-1.0.jar")), "the old name is gone");
            List<Chunker.Cut> oldCuts = cuts(root, before);
            List<Chunker.Cut> newCuts = cuts(root, after);
            List<String> missing = newHashes(newCuts, hashes(oldCuts));
            int expectedReuse = newCuts.size() - missing.size();
            assertTrue(expectedReuse > 0, "the renamed file kept most of its chunks");
            assertEquals(expectedReuse, reportedReuse(player),
                    "the deleted copy's chunks are reused: " + transcript(player));
            assertEquals(bytesOf(newCuts, missing)
                    + metadataBytes(s3, TAG, "2.9.1", List.of("mods/a-1.1.jar")), delta,
                    "only the changed chunks and the metadata came over the wire");
            assertTrue(delta < before.length / 2, "far less than the file: " + delta + " of " + before.length);
            assertArrayEquals(before, Files.readAllBytes(
                    Layout.backupDir(player, TAG, "2.9.0").resolve("mods/a-1.0.jar")));
            assertEquals("2.9.1", applied(player, TAG));
        }
    }

    @Test
    void replacesAndAddsWhatChangedAndBacksUpTheOldFiles(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of(
                    "mods/a.jar", TestFiles.bytes("A"),
                    "mods/b.jar", TestFiles.bytes("B"),
                    "config/x.cfg", TestFiles.bytes("X")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, null);
            FileTime untouched = Files.getLastModifiedTime(player.resolve("config/x.cfg"));

            publish(publisher, TAG, "2.9.1", Map.of(
                    "mods/a.jar", TestFiles.bytes("A2"),
                    "mods/c.jar", TestFiles.bytes("C"),
                    "config/x.cfg", TestFiles.bytes("X")));
            resetLog();
            update(player, TAG, null);

            assertEquals("A2", Files.readString(player.resolve("mods/a.jar")));
            assertEquals("C", Files.readString(player.resolve("mods/c.jar")));
            assertFalse(Files.exists(player.resolve("mods/b.jar")));
            assertEquals(untouched, Files.getLastModifiedTime(player.resolve("config/x.cfg")),
                    "a file whose hash did not change is not rewritten");
            Path backup = Layout.backupDir(player, TAG, "2.9.0");
            assertEquals("A", Files.readString(backup.resolve("mods/a.jar")));
            assertEquals("B", Files.readString(backup.resolve("mods/b.jar")));
            assertEquals("2.9.1", applied(player, TAG));
            String transcript = transcript(player);
            assertTrue(transcript.contains("added mods/c.jar"), transcript);
            assertTrue(transcript.contains("replaced mods/a.jar"), transcript);
            assertTrue(transcript.contains("deleted mods/b.jar"), transcript);
        }
    }

    @Test
    void leavesFilesAloneWhenOnlyTheirLocalCopyChanged(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of(
                    "config/x.cfg", TestFiles.bytes("X"),
                    "mods/b.jar", TestFiles.bytes("B")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, null);

            TestFiles.write(player, "config/x.cfg", "my own settings");
            Files.delete(player.resolve("mods/b.jar"));

            publish(publisher, TAG, "2.9.1", Map.of(
                    "config/x.cfg", TestFiles.bytes("X"),
                    "mods/b.jar", TestFiles.bytes("B"),
                    "mods/c.jar", TestFiles.bytes("C")));
            update(player, TAG, null);

            assertEquals("my own settings", Files.readString(player.resolve("config/x.cfg")));
            assertFalse(Files.exists(player.resolve("mods/b.jar")), "a file the player removed is not put back");
            assertEquals("C", Files.readString(player.resolve("mods/c.jar")));
        }
    }

    @Test
    void fallsBackToAFullDownloadWhenTheReplacedFileHasNoLocalCopy(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] before = TestFiles.random(300_000, 41);
            byte[] after = before.clone();
            after[120_000] ^= 0x33;
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", before));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, null);
            long already = s3.downloadedBytes();

            Files.delete(player.resolve("mods/a.jar"));
            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", after));
            resetLog();
            update(player, TAG, null);
            long delta = s3.downloadedBytes() - already;

            assertArrayEquals(after, Files.readAllBytes(player.resolve("mods/a.jar")));
            assertEquals(0, reportedReuse(player));
            assertFalse(transcript(player).contains("reused "), "nothing was reused");
            assertEquals(after.length + metadataBytes(s3, TAG, "2.9.1", List.of("mods/a.jar")), delta,
                    "with no local copy every chunk is fetched again");
            assertEquals("2.9.1", applied(player, TAG));
        }
    }

    @Test
    void fallsBackToAFullDownloadWhenTheLocalCopyCannotBeRead(@TempDir Path root) throws IOException {
        assumeTrue(Path.of(".").getFileSystem().supportedFileAttributeViews().contains("posix"),
                "the platform has no POSIX file permissions");
        try (FakeS3 s3 = new FakeS3()) {
            byte[] before = TestFiles.random(300_000, 45);
            byte[] after = before.clone();
            after[80_000] ^= 0x44;
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", before));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, null);
            long already = s3.downloadedBytes();

            Path local = player.resolve("mods/a.jar");
            Files.setPosixFilePermissions(local, PosixFilePermissions.fromString("---------"));
            assumeFalse(Files.isReadable(local), "this user can read the local copy anyway");
            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", after));
            resetLog();
            update(player, TAG, null);
            long delta = s3.downloadedBytes() - already;

            assertArrayEquals(after, Files.readAllBytes(player.resolve("mods/a.jar")));
            assertEquals(0, reportedReuse(player),
                    "a local copy that cannot be read is as good as no copy");
            assertEquals(after.length + metadataBytes(s3, TAG, "2.9.1", List.of("mods/a.jar")), delta,
                    "the unreadable copy forced a full download, not a failure");
        }
    }

    @Test
    void refusesAFileWhoseChunksDoNotAssembleIntoTheManifestHash(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] before = TestFiles.random(300_000, 51);
            byte[] after = before.clone();
            after[100_000] ^= 0x11;
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", before));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, null);

            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", after));
            List<String> missing = newHashes(cuts(root, after), hashes(cuts(root, before)));
            assertFalse(missing.isEmpty(), "the changed content has a chunk of its own");
            s3.put(chunkKey(missing.get(0)), TestFiles.bytes("not the chunk it is named after"));

            UserError error = assertThrows(UserError.class,
                    () -> update(player, TAG, null));

            assertTrue(error.getMessage().contains("manifest"), error.getMessage());
            assertArrayEquals(before, Files.readAllBytes(player.resolve("mods/a.jar")),
                    "a failed assembly leaves the old file in place");
            assertFalse(Files.exists(Layout.backupDir(player, TAG, "2.9.0").resolve("mods/a.jar")),
                    "nothing is moved aside before the pieces are known to assemble");
            assertNothingStaged(player);
            assertEquals("2.9.0", applied(player, TAG));
        }
    }

    @Test
    void refusesAManifestItCannotRead(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", TestFiles.bytes("A")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, null);
            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", TestFiles.bytes("A2")));
            s3.fail("GET", releaseKey(TAG, "2.9.1"), 500);
            int requestsBefore = s3.requests().size();
            int transcriptBefore = transcript(player).length();

            UserError error = assertThrows(UserError.class,
                    () -> update(player, TAG, null));

            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
            assertEquals("A", Files.readString(player.resolve("mods/a.jar")),
                    "a run that cannot read the target manifest touches no file");
            assertEquals("2.9.0", applied(player, TAG));
            assertNothingStaged(player);
            List<String> requests = s3.requests().subList(requestsBefore, s3.requests().size());
            assertTrue(requests.stream().noneMatch(request -> request.startsWith("GET " + TestWorkspace.bucketPath(
                    ObjectKeys.BLOB + "/"))), requests.toString());
            List<String> stages = stageStarts(transcript(player).substring(transcriptBefore));
            assertEquals("fetching target manifest for release 2.9.1", stages.get(stages.size() - 1),
                    "the failing stage is logged before its request, not only after success");
        }
    }

    @Test
    void readsEveryObjectThroughTheReadEndpointWithoutCredentials(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] jar = TestFiles.random(300_000, 21);
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", jar, "mmc-pack.json", TestFiles.bytes("{}")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, "http://127.0.0.1:1", s3.url() + "/" + TestWorkspace.BUCKET);
            s3.allowAnonymous(true);
            int before = s3.requestsDetailed().size();

            update(player, TAG, null);

            assertArrayEquals(jar, Files.readAllBytes(player.resolve("mods/a.jar")));
            assertEquals("2.9.0", applied(player, TAG));
            List<FakeS3.Request> reads = s3.requestsDetailed().subList(before, s3.requestsDetailed().size());
            assertFalse(reads.isEmpty(), "the update read something");
            for (FakeS3.Request request : reads) {
                assertEquals("GET", request.method(), request.toString());
                assertTrue(request.path().startsWith(TestWorkspace.bucketPath("")), request.toString());
                assertFalse(request.headers().containsKey("authorization"), request.toString());
                assertFalse(request.headers().containsKey("x-amz-date"), request.toString());
                assertEquals("identity", request.headers().get("accept-encoding"), request.toString());
            }

            int beforeSecond = s3.requests().size();
            int transcriptBeforeSecond = transcript(player).length();
            update(player, TAG, null);
            assertEquals(List.of("GET " + latestKey(TAG)),
                    s3.requests().subList(beforeSecond, s3.requests().size()),
                    "a workspace that is already at the latest release only reads its pointer");
            String secondTranscript = transcript(player).substring(transcriptBeforeSecond);
            assertEquals(List.of(
                    "loading workspace configuration", "validating signing key paths", "loading trusted public key",
                    "locating updater and workspace", "initializing object store", "resolving latest release",
                    "reading local release pointer"), stageStarts(secondTranscript),
                    "a workspace that is already at the latest release skips the old manifest preflight");
            assertFalse(captured.outText().contains("tag client:"), "stage details stay out of the console");
            List<String> messages = captured.outText().lines().map(UpdateTest::body).toList();
            assertEquals("tag client is already at release 2.9.0", messages.get(messages.size() - 1));
        }
    }

    @Test
    void refusesToReadThroughTheReadEndpointAndLeavesTheWorkspaceAlone(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", TestFiles.bytes("A")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, "http://127.0.0.1:1", s3.url() + "/" + TestWorkspace.BUCKET);
            s3.allowAnonymous(true);
            String latest = latestKey(TAG);
            s3.fail("GET", latest, 500);
            int before = s3.requests().size();

            UserError error = assertThrows(UserError.class, () -> update(player, TAG, null));

            String readUrl = s3.url() + latest;
            assertTrue(error.getMessage().contains(readUrl), error.getMessage());
            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
            assertFalse(Files.exists(Layout.currentFile(player, TAG)), "the pointer did not move");
            assertNothingStaged(player);
            assertEquals(List.of("GET " + latest), s3.requests().subList(before, s3.requests().size()),
                    "the failed read did not fall back to the write endpoint");
            assertEquals(List.of(),
                    s3.requests().subList(before, s3.requests().size()).stream()
                            .filter(request -> request.startsWith("PUT ")).toList());
        }
    }

    @Test
    void refusesAChunkItCannotDownloadAndLeavesTheWorkspaceRecoverable(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", TestFiles.bytes("A")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, null);
            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", TestFiles.bytes("A2")));
            String missing = Sha256.ofBytes(TestFiles.bytes("A2"));
            s3.fail("GET", chunkKey(missing), 500);

            UserError error = assertThrows(UserError.class,
                    () -> update(player, TAG, null));

            assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
            assertEquals("2.9.0", applied(player, TAG), "the pointer did not move");
            assertEquals("A", Files.readString(player.resolve("mods/a.jar")),
                    "the file being replaced stays where it is");
            assertFalse(Files.exists(Layout.backupDir(player, TAG, "2.9.0").resolve("mods/a.jar")),
                    "nothing is moved aside before every chunk is here");
            try (var walk = Files.walk(player)) {
                List<Path> leftovers = walk.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().startsWith(".update-")
                                || file.getFileName().toString().endsWith(".part"))
                        .toList();
                assertEquals(List.of(), leftovers, "no half-written file is left behind");
            }
            assertNothingStaged(player);
        }
    }

    @Test
    void reportsAPointerWithoutItsManifest(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", TestFiles.bytes("A")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            update(player, TAG, null);
            Files.delete(Layout.releaseFile(player, TAG, "2.9.0"));

            UserError error = assertThrows(UserError.class,
                    () -> update(player, TAG, null));
            assertTrue(error.getMessage().contains("is missing"), error.getMessage());
        }
    }

    @Test
    void reportsATagThatWasNeverPublished(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);

            UserError error = assertThrows(UserError.class,
                    () -> update(player, TAG, null));
            assertTrue(error.getMessage().contains("not found"), error.getMessage());
        }
    }

    @Test
    void reusesTheKeptCopiesOfTheReleaseItRollsBackTo(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            byte[] before = TestFiles.random(300_000, 85);
            byte[] after = before.clone();
            after[90_000] ^= 0x13;
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", before));
            publish(publisher, TAG, "2.9.1", Map.of("mods/a.jar", after));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);

            update(player, TAG, "2.9.0");
            update(player, TAG, null);
            assertEquals("2.9.1", applied(player, TAG));
            long forwarded = s3.downloadedBytes();

            resetLog();
            update(player, TAG, "2.9.0");
            long delta = s3.downloadedBytes() - forwarded;

            assertEquals("2.9.0", applied(player, TAG));
            assertArrayEquals(before, Files.readAllBytes(player.resolve("mods/a.jar")));
            assertEquals(hashes(cuts(root, before)).size(), reportedReuse(player),
                    "every target chunk came from the copy kept under backup/2.9.0: "
                            + transcript(player));
            List<String> bodies = captured.outText().lines().map(UpdateTest::body).toList();
            assertTrue(bodies.get(bodies.size() - 2).startsWith("downloaded nothing"),
                    "nothing came over the wire: " + captured.outText());
            String table = manifest(s3, TAG, "2.9.0").files().get("mods/a.jar").indexId();
            assertEquals(s3.object(indexKey(table)).length + s3.object(releaseKey(TAG, "2.9.0")).length
                            + s3.object(signatureKey(TAG, "2.9.0")).length, delta,
                    "only the chunk table, manifest and signature came over the wire: " + s3.requests());
        }
    }

    @Test
    void appliesTheReleaseNamedOnTheCommandLineInsteadOfLatest(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of(
                    "mods/a.jar", TestFiles.bytes("A"),
                    "mods/b.jar", TestFiles.bytes("B")));
            publish(publisher, TAG, "2.9.1", Map.of(
                    "mods/a.jar", TestFiles.bytes("A2"),
                    "mods/b.jar", TestFiles.bytes("B")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            resetLog();

            update(player, TAG, "2.9.0");

            assertEquals("2.9.0", applied(player, TAG), "the named release, not the latest one");
            assertEquals("A", Files.readString(player.resolve("mods/a.jar")));
            assertEquals(0, getCount(s3, latestKey(TAG)), "a pinned update never reads latest: " + s3.requests());

            resetLog();
            update(player, TAG, "2.9.1");
            assertEquals("2.9.1", applied(player, TAG));
            assertEquals("A2", Files.readString(player.resolve("mods/a.jar")));

            resetLog();
            update(player, TAG, "2.9.0");
            assertEquals("2.9.0", applied(player, TAG), "the same option rolls the workspace back");
            assertEquals("A", Files.readString(player.resolve("mods/a.jar")));
            assertEquals("A2", Files.readString(
                    Layout.backupDir(player, TAG, "2.9.1").resolve("mods/a.jar")));
            assertEquals(0, getCount(s3, latestKey(TAG)));

            resetLog();
            update(player, TAG, "2.9.0");
            assertTrue(captured.outText().contains("is already at release 2.9.0"), captured.outText());
        }
    }

    @Test
    void keepsEachTagInItsOwnStateDirectory(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "1.0", Map.of("mods/a.jar", TestFiles.bytes("A")));
            publish(publisher, "client-extra", "1.0", Map.of("shaderpacks/s.zip", TestFiles.bytes("S")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);

            update(player, TAG, null);
            update(player, "client-extra", null);

            assertEquals("1.0", applied(player, TAG));
            assertEquals("1.0", applied(player, "client-extra"));
            assertEquals("A", Files.readString(player.resolve("mods/a.jar")));
            assertEquals("S", Files.readString(player.resolve("shaderpacks/s.zip")));
        }
    }

    @Test
    void keepsTheTranscriptOfARunThatTransferred(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of("mods/a.jar", TestFiles.bytes("A")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);

            update(player, TAG, null);

            List<Path> logs = logs(player);
            assertEquals(1, logs.size(), logs.toString());
            assertTrue(logs.get(0).getFileName().toString().contains("update-client"),
                    logs.get(0).toString());
            String text = Files.readString(logs.get(0), StandardCharsets.UTF_8);
            assertTrue(text.contains("updating tag client to release 2.9.0"), text);
            assertTrue(text.contains("added mods/a.jar"), text);
            assertTrue(text.contains("downloaded 1 chunk"), text);
            assertTrue(text.contains("updated tag client to release 2.9.0 in "), text);

            resetLog();
            update(player, TAG, null);
            assertEquals(1, logs(player).size(), "a run with nothing to transfer leaves no second log");
        }
    }

    @Test
    void reportsTheRunFromStartToFinish(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            publish(publisher, TAG, "2.9.0", Map.of(
                    "mods/a.jar", TestFiles.bytes("A"),
                    "mods/b.jar", TestFiles.bytes("B")));
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);
            resetLog();

            update(player, TAG, null);

            List<String> lines = captured.outText().lines().toList();
            List<String> bodies = lines.stream().map(UpdateTest::body).toList();
            assertTrue(bodies.get(0).startsWith("logging this run to "), lines.toString());
            int start = bodies.indexOf("updating tag client to release 2.9.0");
            assertTrue(start > 0, lines.toString());
            assertTrue(bodies.subList(start, bodies.size()).stream()
                    .anyMatch(message -> message.startsWith("2 files to apply: ")), lines.toString());
            assertTrue(bodies.stream().anyMatch(message -> message.startsWith("wrote ")), lines.toString());
            assertTrue(bodies.get(bodies.size() - 2).startsWith("downloaded 2 files, "), lines.toString());
            assertTrue(bodies.get(bodies.size() - 1)
                    .startsWith("updated tag client to release 2.9.0 in "), lines.toString());
            for (String heading : List.of("plan", "apply", "commit")) {
                assertFalse(bodies.contains(heading), "no bare stage heading: " + lines);
            }
        }
    }
}

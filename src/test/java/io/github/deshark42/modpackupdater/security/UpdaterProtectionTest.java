package io.github.deshark42.modpackupdater.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.deshark42.modpackupdater.Main;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.FileEntry;
import io.github.deshark42.modpackupdater.model.Manifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UpdaterProtectionTest {

    private static Manifest manifest(String path) {
        return new Manifest("client", "v1", Instant.EPOCH, Manifest.SHA256,
                Map.of(path, new FileEntry("0".repeat(64), 0, "0".repeat(64))));
    }

    @Test
    void locatesTheLoadedEntryPointRatherThanAssumingAJarName() throws Exception {
        assertEquals(Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                UpdaterProtection.runningUpdater());
    }

    @ParameterizedTest(name = "payload {0} conflicts with the renamed updater")
    @ValueSource(strings = {"tools/custom runner.bin", "tools", "tools/custom runner.bin/child"})
    void rejectsTheUpdaterAndItsPathPrefixes(String path, @TempDir Path root) throws IOException {
        TestFiles.write(root, "tools/custom runner.bin", "updater");
        UpdaterProtection protection = new UpdaterProtection(root, root.resolve("tools/custom runner.bin"));

        UserError error = assertThrows(UserError.class, () -> protection.require(manifest(path)));

        assertTrue(error.getMessage().contains("running updater"), error.getMessage());
        assertEquals("updater", Files.readString(root.resolve("tools/custom runner.bin")));
    }

    @Test
    void protectsTheClassesDirectoryWhenRunningWithoutAJar(@TempDir Path root) throws IOException {
        TestFiles.write(root, "classes/Main.class", "bytecode");
        UpdaterProtection protection = new UpdaterProtection(root, root.resolve("classes"));

        UserError error = assertThrows(UserError.class,
                () -> protection.require(manifest("classes/new/package/Helper.class")));

        assertTrue(error.getMessage().contains("running updater"), error.getMessage());
        assertEquals("bytecode", Files.readString(root.resolve("classes/Main.class")));
    }

    @ParameterizedTest(name = "unrelated payload {0} remains allowed")
    @ValueSource(strings = {"mods/a.jar", "tools/custom runner.bin-old", "tools/sibling.jar",
            "other/custom runner.bin", "start.sh", "start.bat"})
    void permitsUnrelatedPayloads(String path, @TempDir Path root) throws IOException {
        TestFiles.write(root, "tools/custom runner.bin", "updater");
        UpdaterProtection protection = new UpdaterProtection(root, root.resolve("tools/custom runner.bin"));

        protection.require(manifest(path));

        assertEquals("updater", Files.readString(root.resolve("tools/custom runner.bin")));
    }

    @Test
    void permitsPayloadsWhenTheUpdaterIsOutsideTheWorkspace(@TempDir Path root) throws IOException {
        Path workspace = Files.createDirectory(root.resolve("instance"));
        TestFiles.write(root, "runner.jar", "external updater");
        UpdaterProtection protection = new UpdaterProtection(workspace, root.resolve("runner.jar"));

        protection.require(manifest("runner.jar"));

        assertEquals("external updater", Files.readString(root.resolve("runner.jar")));
    }

    @Test
    void rejectsAFileIdentityAliasToAnExternalUpdater(@TempDir Path root) throws IOException {
        Path workspace = Files.createDirectory(root.resolve("instance"));
        TestFiles.write(root, "runner.jar", "external updater");
        try {
            Files.createLink(workspace.resolve("alias.jar"), root.resolve("runner.jar"));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "hard links require filesystem support: " + e);
        }
        UpdaterProtection protection = new UpdaterProtection(workspace, root.resolve("runner.jar"));

        UserError error = assertThrows(UserError.class, () -> protection.require(manifest("alias.jar")));

        assertTrue(error.getMessage().contains("running updater"), error.getMessage());
        assertEquals("external updater", Files.readString(root.resolve("runner.jar")));
    }

    @Test
    void protectsThePhysicalWorkspaceWhenItsSelectedRootIsALink(@TempDir Path root) throws IOException {
        Path workspace = Files.createDirectory(root.resolve("instance"));
        TestFiles.write(workspace, "runner.jar", "updater");
        Path selected = root.resolve("selected");
        try {
            Files.createSymbolicLink(selected, workspace);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "directory symbolic links require filesystem support and privileges: " + e);
        }
        UpdaterProtection protection = new UpdaterProtection(selected, workspace.resolve("runner.jar"));

        UserError error = assertThrows(UserError.class, () -> protection.require(manifest("runner.jar")));

        assertTrue(error.getMessage().contains("running updater"), error.getMessage());
        assertEquals("updater", Files.readString(workspace.resolve("runner.jar")));
    }

    @Test
    void refusesToSkipProtectionWhenTheUpdaterCannotBeLocated(@TempDir Path root) {
        UserError error = assertThrows(UserError.class,
                () -> new UpdaterProtection(root, root.resolve("missing.jar")));
        assertTrue(error.getMessage().contains("cannot locate the running updater"), error.getMessage());
    }
}

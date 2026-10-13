package io.github.deshark42.modpackupdater.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {

    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();
    private final ByteArrayOutputStream printedErrors = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(printed, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(printedErrors, true, StandardCharsets.UTF_8);

    private int run(Path cwd, String... args) {
        return Cli.run(args, cwd, out, err);
    }

    private static void write(Path root, String name, String content) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void configure(Path workspace, FakeS3 s3) throws IOException {
        write(workspace, ".modpack-updater/config.json", "{\"endpoint\": \"" + s3.url()
                + "\", \"bucket\": \"pack\", \"prefix\": \"gtnh\", \"path_style\": true,"
                + " \"access_key\": \"AK\", \"secret_key\": \"SK\"}");
    }

    @Test
    void noCommandIsAUsageError(@TempDir Path cwd) {
        assertEquals(Cli.USAGE_ERROR, run(cwd));
        assertTrue(printedErrors.toString().contains("usage: modpack-updater"), printedErrors.toString());
    }

    @Test
    void unknownOptionAndUnknownCommandAreUsageErrors(@TempDir Path cwd) {
        assertEquals(Cli.USAGE_ERROR, run(cwd, "update", "--tag", "client", "--wat"));
        assertTrue(printedErrors.toString().contains("unknown option: --wat"), printedErrors.toString());
        assertEquals(Cli.USAGE_ERROR, run(cwd, "sync", "--tag", "client"));
        assertTrue(printedErrors.toString().contains("unknown command: sync"), printedErrors.toString());
    }

    @Test
    void eachCommandChecksItsOwnOptions(@TempDir Path cwd) {
        assertEquals(Cli.USAGE_ERROR, run(cwd, "update"));
        assertTrue(printedErrors.toString().contains("needs --tag"), printedErrors.toString());
        assertEquals(Cli.USAGE_ERROR, run(cwd, "publish", "--tag", "client"));
        assertTrue(printedErrors.toString().contains("needs --id"), printedErrors.toString());
        assertEquals(Cli.USAGE_ERROR, run(cwd, "update", "--tag", "client", "--id", "1.0"));
        assertTrue(printedErrors.toString().contains("does not take --id"), printedErrors.toString());
        assertEquals(Cli.USAGE_ERROR, run(cwd, "update", "--tag"));
        assertTrue(printedErrors.toString().contains("needs a value"), printedErrors.toString());
    }

    @Test
    void aMissingWorkspaceIsAFailureNotAUsageError(@TempDir Path cwd) {
        assertEquals(Cli.FAILURE, run(cwd, "update", "--tag", "client"));
        assertTrue(printedErrors.toString().startsWith("error: "), printedErrors.toString());
    }

    @Test
    void dryRunPublishNeedsNoConnection(@TempDir Path cwd) throws IOException {
        write(cwd, "client/base/mods/a.jar", "A");

        assertEquals(Cli.OK, run(cwd, "publish", "--tag", "client", "--id", "2.9.0", "--dry-run"));

        assertTrue(printed.toString().contains("dry run"), printed.toString());
    }

    @Test
    void publishesAndUpdatesThroughTheCli(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            configure(publisher, s3);
            write(publisher, "client/base/mods/a.jar", "A");

            assertEquals(Cli.OK, run(publisher, "publish", "--tag", "client", "--id", "2.9.0"), printedErrors.toString());

            Path player = root.resolve("player");
            configure(player, s3);
            assertEquals(Cli.OK, run(root, "update", "--tag", "client", "--workspace", player.toString()),
                    printedErrors.toString());

            assertEquals("A", Files.readString(player.resolve("mods/a.jar")));
            assertTrue(printed.toString().contains("is now at 2.9.0"), printed.toString());
        }
    }
}

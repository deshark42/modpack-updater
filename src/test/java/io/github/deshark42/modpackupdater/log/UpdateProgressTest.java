package io.github.deshark42.modpackupdater.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.command.Publish;
import io.github.deshark42.modpackupdater.command.Update;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateProgressTest {

    private static final String TAG = "client";

    /** Runs an update with a bar that draws on a fake clock, and returns what it wrote to the error stream. */
    private String update(Path workspace) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            AtomicLong nanos = new AtomicLong();
            Progress progress = new Progress(err, true, () -> nanos.addAndGet(1_000_000_000L));
            Logging.begin(workspace, null, out, err, Clock.systemUTC(), progress);
            try {
                Update.run(workspace, TAG, null);
            } finally {
                Logging.finish();
            }
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    /** The frames of one bar, in the order they were drawn. */
    private static List<String> frames(String output, String verb) {
        return Arrays.stream(output.split("\r"))
                .filter(frame -> frame.startsWith("  " + verb + " "))
                .toList();
    }

    @Test
    void hashingTheFilesToApplyDrawsOneBarOverTheirTotal(@TempDir Path root) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            Path publisher = root.resolve("publisher");
            TestWorkspace.configure(publisher, s3);
            TestWorkspace.base(publisher, TAG, "mods/a.jar", "A");
            TestWorkspace.base(publisher, TAG, "mods/b.jar", "B");
            TestWorkspace.base(publisher, TAG, "mods/c.jar", "C");
            Publish.run(publisher, TAG, "2.9.0");
            Path player = root.resolve("player");
            TestWorkspace.configure(player, s3);

            String output = update(player);

            List<String> check = frames(output, "check");
            assertEquals(2, check.size(), output);
            for (int done = 1; done < 3; done++) {
                int count = done;
                assertTrue(check.stream().anyMatch(frame -> frame.contains(count + "/3 files")), output);
            }
            assertTrue(check.stream().allMatch(frame -> frame.contains("file/s")), output);
            assertFalse(check.stream().anyMatch(frame -> frame.contains("B/s")),
                    "a check moves no bytes: " + output);
            assertTrue(output.endsWith("\r\u001b[K"), "the finished bar is erased: " + output);
        }
    }
}

package io.github.deshark42.modpackupdater.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.FakeS3;
import io.github.deshark42.modpackupdater.TestWorkspace;
import io.github.deshark42.modpackupdater.command.Publish;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublishProgressTest {

    private static final String TAG = "client";

    private String publish(Path workspace, String releaseId) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            AtomicLong nanos = new AtomicLong();
            Progress progress = new Progress(err, true, () -> nanos.addAndGet(1_000_000_000L));
            Logging.begin(workspace, null, out, err, Clock.systemUTC(), progress);
            try {
                Publish.run(workspace, TAG, releaseId);
            } finally {
                Logging.finish();
            }
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static List<String> checkFrames(String output) {
        return Arrays.stream(output.split("\r"))
                .filter(frame -> frame.startsWith("  check "))
                .toList();
    }

    private static boolean blobRequest(String request) {
        return request.contains("/blob/") || request.contains("/index/");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void checksDistinctChunksAndTablesOnOneBarEvenWhenCached(boolean cached, @TempDir Path workspace)
            throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            TestWorkspace.base(workspace, TAG, "mods/a.jar", "A");
            TestWorkspace.base(workspace, TAG, "mods/duplicate.jar", "A");
            TestWorkspace.base(workspace, TAG, "mods/b.jar", "B");
            if (cached) {
                publish(workspace, "first");
            }
            int before = s3.requests().size();

            String output = publish(workspace, "second");

            List<String> frames = checkFrames(output);
            assertEquals(3, frames.size(), output);
            for (int done = 1; done < 4; done++) {
                int count = done;
                assertTrue(frames.stream().anyMatch(frame -> frame.contains(count + "/4 objects")), output);
            }
            assertTrue(frames.stream().allMatch(frame ->
                    frame.contains("object/s") || frame.contains("objects/s")), output);
            assertFalse(output.contains("B/s"), output);
            assertTrue(output.endsWith("\r\u001b[K"), output);
            List<String> requests = s3.requests().subList(before, s3.requests().size());
            assertEquals(cached ? 0 : 4,
                    requests.stream().filter(request -> request.startsWith("HEAD "))
                            .filter(PublishProgressTest::blobRequest).count());
            assertEquals(cached ? 0 : 4,
                    requests.stream().filter(request -> request.startsWith("PUT "))
                            .filter(PublishProgressTest::blobRequest).count());
        }
    }

    @Test
    void anEmptyReleaseDoesNotLeaveACheckBar(@TempDir Path workspace) throws IOException {
        try (FakeS3 s3 = new FakeS3()) {
            TestWorkspace.configure(workspace, s3);
            Files.createDirectories(Layout.baseDir(workspace, TAG));

            assertEquals("", publish(workspace, "empty"));
        }
    }
}

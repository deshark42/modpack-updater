package io.github.deshark42.modpackupdater.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.deshark42.modpackupdater.Main;
import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.PathGuard;
import io.github.deshark42.modpackupdater.model.FileEntry;
import io.github.deshark42.modpackupdater.model.Manifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UpdaterProtectionTest {

    private static Manifest manifest(String path) {
        return new Manifest("client", "v1", Instant.EPOCH, Manifest.SHA256,
                Map.of(path, new FileEntry("0".repeat(64), 0, "0".repeat(64))));
    }

    private static Manifest largeManifest(String path) {
        Map<String, FileEntry> files = new HashMap<>(manifest(path).files());
        for (int i = 0; i < UpdaterProtection.MIN_PATHS_PER_WORKER * UpdaterProtection.MAX_PATH_WORKERS; i++) {
            files.put("padding/missing-" + i, files.get(path));
        }
        return new Manifest("client", "v1", Instant.EPOCH, Manifest.SHA256, files);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "worker coordination timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("worker coordination interrupted", e);
        }
    }

    @ParameterizedTest(name = "path checks with concurrency {0}")
    @ValueSource(ints = {1, 2, 8, 64})
    void checksEveryPathOnceWithBoundedConcurrentBatches(int concurrency) {
        List<Integer> items = IntStream.range(0,
                UpdaterProtection.MAX_PATH_WORKERS * UpdaterProtection.MIN_PATHS_PER_WORKER + 17).boxed().toList();
        int workers = Math.min(concurrency, UpdaterProtection.MAX_PATH_WORKERS);
        CountDownLatch started = new CountDownLatch(workers);
        List<Integer> checked = Collections.synchronizedList(new ArrayList<>());
        Set<Thread> threads = ConcurrentHashMap.newKeySet();

        UpdaterProtection.checkBatches(items, concurrency, batch -> {
            threads.add(Thread.currentThread());
            started.countDown();
            await(started);
            checked.addAll(batch);
        });

        assertEquals(workers, threads.size());
        assertEquals(items.size(), checked.size());
        assertEquals(new HashSet<>(items), new HashSet<>(checked));
    }

    @Test
    void checksSmallManifestsOnTheCallingThread() {
        Thread caller = Thread.currentThread();
        List<Integer> checked = new ArrayList<>();
        UpdaterProtection.checkBatches(List.of(1, 2), 64, batch -> {
            assertSame(caller, Thread.currentThread());
            checked.addAll(batch);
        });
        assertEquals(List.of(1, 2), checked);
    }

    @Test
    void waitsForReadersToStopOnFailure() throws Exception {
        waitsForReadersToStop(false);
    }

    @Test
    void waitsForReadersToStopOnInterruption() throws Exception {
        waitsForReadersToStop(true);
    }

    private static void waitsForReadersToStop(boolean interrupt) throws Exception {
        List<Integer> items = IntStream.range(0, UpdaterProtection.MIN_PATHS_PER_WORKER * 2).boxed().toList();
        CountDownLatch started = new CountDownLatch(interrupt ? 2 : 1);
        CountDownLatch stopping = new CountDownLatch(interrupt ? 2 : 1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptedAtReturn = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                UpdaterProtection.checkBatches(items, 2, batch -> {
                    active.incrementAndGet();
                    try {
                        if (!interrupt && batch.get(0) == 0) {
                            await(started);
                            throw new UserError("failed preflight");
                        }
                        started.countDown();
                        try {
                            assertTrue(blocked.await(5, TimeUnit.SECONDS), "worker was not cancelled");
                        } catch (InterruptedException e) {
                            stopping.countDown();
                            await(releaseCleanup);
                        }
                    } finally {
                        active.decrementAndGet();
                    }
                });
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                interruptedAtReturn.set(Thread.currentThread().isInterrupted());
                returned.countDown();
            }
        });
        caller.start();
        try {
            await(started);
            if (interrupt) {
                caller.interrupt();
            }
            await(stopping);
            assertFalse(returned.await(50, TimeUnit.MILLISECONDS),
                    "preflight returned while a cancelled reader was still in cleanup");
        } finally {
            releaseCleanup.countDown();
            blocked.countDown();
            caller.join(5000);
            if (caller.isAlive()) {
                caller.interrupt();
                caller.join(5000);
            }
        }
        assertFalse(caller.isAlive(), "preflight did not finish after readers stopped");
        assertEquals(0, active.get());
        UserError error = assertInstanceOf(UserError.class, failure.get());
        assertEquals(interrupt ? "interrupted" : "failed preflight", error.getMessage());
        assertEquals(interrupt, interruptedAtReturn.get());
    }

    @ParameterizedTest(name = "parallel preflight rejects native alias before {0}")
    @ValueSource(strings = {"existing.jar", "missing/a.jar", "blocked.jar/child"})
    @EnabledOnOs(OS.WINDOWS)
    void parallelPreflightStillRejectsNativeDirectoryAliases(String suffix, @TempDir Path root) throws IOException {
        TestFiles.write(root, "runner.jar", "updater");
        TestFiles.write(root, "mods/existing.jar", "ordinary payload");
        TestFiles.write(root, "mods/blocked.jar", "ordinary blocker");
        UpdaterProtection protection = new UpdaterProtection(root, root.resolve("runner.jar"));

        UserError error = assertThrows(UserError.class,
                () -> protection.require(largeManifest("mods./" + suffix), UpdaterProtection.MAX_PATH_WORKERS));

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        assertEquals("ordinary payload", Files.readString(root.resolve("mods/existing.jar")));
        assertEquals("updater", Files.readString(root.resolve("runner.jar")));
    }

    private static void junction(Path link, Path target) throws Exception {
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        try (var output = process.getInputStream()) {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "junction creation timed out");
            assertEquals(0, process.exitValue(), new String(output.readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void parallelPreflightRechecksJunctionsBetweenManifests(@TempDir Path root) throws Exception {
        parallelPreflightRechecksJunctionsBetweenManifests(false, root);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void parallelPreflightRechecksJunctionsBelowASelectedRootLink(@TempDir Path root) throws Exception {
        parallelPreflightRechecksJunctionsBetweenManifests(true, root);
    }

    private static void parallelPreflightRechecksJunctionsBetweenManifests(boolean rootLink, Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("instance"));
        TestFiles.write(workspace, "runner.jar", "updater");
        TestFiles.write(workspace, "mods/a.jar", "ordinary payload");
        Path outside = Files.createDirectory(root.resolve("outside folder"));
        TestFiles.write(outside, "a.jar", "external payload");
        Path selected = workspace;
        if (rootLink) {
            selected = root.resolve("selected");
            junction(selected, workspace);
        }
        UpdaterProtection protection = new UpdaterProtection(selected, workspace.resolve("runner.jar"));
        Manifest payload = largeManifest("mods/a.jar");
        protection.require(payload, UpdaterProtection.MAX_PATH_WORKERS);

        Files.delete(workspace.resolve("mods/a.jar"));
        Files.delete(workspace.resolve("mods"));
        junction(workspace.resolve("mods"), outside);

        UserError error = assertThrows(UserError.class,
                () -> protection.require(payload, UpdaterProtection.MAX_PATH_WORKERS));

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        assertEquals("external payload", Files.readString(outside.resolve("a.jar")));
        assertEquals("updater", Files.readString(workspace.resolve("runner.jar")));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void reusesOnePreflightOnlyForThePathsItAlreadyCovered(@TempDir Path root) throws Exception {
        TestFiles.write(root, "runner.jar", "updater");
        TestFiles.write(root, "mods/a.jar", "ordinary payload");
        Path outside = Files.createDirectory(root.resolve("outside"));
        junction(root.resolve("extra"), outside);
        UpdaterProtection protection = new UpdaterProtection(root, root.resolve("runner.jar"));
        PathGuard.Preflight preflight = PathGuard.preflight();
        protection.require(largeManifest("mods/a.jar"), UpdaterProtection.MAX_PATH_WORKERS, preflight);

        UserError error = assertThrows(UserError.class, () -> protection.require(largeManifest("extra/a.jar"),
                UpdaterProtection.MAX_PATH_WORKERS, preflight));

        assertTrue(error.getMessage().contains("filesystem link or alias"), error.getMessage());
        assertEquals("ordinary payload", Files.readString(root.resolve("mods/a.jar")));
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
    void rejectsAResizedUpdaterHardLinkEvenWithMisleadingMetadata(@TempDir Path root) throws IOException {
        Path workspace = Files.createDirectory(root.resolve("instance"));
        TestFiles.write(root, "runner.jar", "external updater");
        try {
            Files.createLink(workspace.resolve("alias.jar"), root.resolve("runner.jar"));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "hard links require filesystem support: " + e);
        }
        // NTFS may retain the old size in the alias's directory entry after this write.
        Files.writeString(root.resolve("runner.jar"), "resized external updater");
        UpdaterProtection protection = new UpdaterProtection(workspace, root.resolve("runner.jar"));
        assertEquals(0, manifest("alias.jar").files().get("alias.jar").size());
        assertEquals("resized external updater", Files.readString(workspace.resolve("alias.jar")),
                "the hard link contains bytes despite a zero-sized manifest entry");

        UserError error = assertThrows(UserError.class, () -> protection.require(largeManifest("alias.jar"), UpdaterProtection.MAX_PATH_WORKERS));

        assertTrue(error.getMessage().contains("running updater"), error.getMessage());
        assertEquals("resized external updater", Files.readString(root.resolve("runner.jar")));
    }

    @ParameterizedTest(name = "unrelated existing content: {0}")
    @ValueSource(strings = {"", "payload", "a larger payload"})
    void permitsExistingFilesRegardlessOfTheirSize(String content, @TempDir Path root) throws IOException {
        TestFiles.write(root, "runner.jar", "updater");
        TestFiles.write(root, "mods/a.jar", content);
        UpdaterProtection protection = new UpdaterProtection(root, root.resolve("runner.jar"));

        protection.require(largeManifest("mods/a.jar"), UpdaterProtection.MAX_PATH_WORKERS);

        assertEquals(content, Files.readString(root.resolve("mods/a.jar")));
        assertEquals("updater", Files.readString(root.resolve("runner.jar")));
    }

    @Test
    void rechecksPayloadIdentityOnEachManifest(@TempDir Path root) throws IOException {
        TestFiles.write(root, "runner.jar", "updater");
        TestFiles.write(root, "mods/a.jar", "ordinary payload");
        Path updater = root.resolve("runner.jar");
        Path payload = root.resolve("mods/a.jar");
        UpdaterProtection protection = new UpdaterProtection(root, updater);
        protection.require(largeManifest("mods/a.jar"), UpdaterProtection.MAX_PATH_WORKERS);
        Files.writeString(updater, "resized updater");
        Files.delete(payload);
        try {
            Files.createLink(payload, updater);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "hard links require filesystem support: " + e);
        }

        UserError error = assertThrows(UserError.class, () -> protection.require(largeManifest("mods/a.jar"), UpdaterProtection.MAX_PATH_WORKERS));

        assertTrue(error.getMessage().contains("running updater"), error.getMessage());
        assertEquals("resized updater", Files.readString(updater));
        assertEquals("resized updater", Files.readString(payload));
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
        assertFalse(Files.exists(root.resolve("missing.jar")));
    }
}

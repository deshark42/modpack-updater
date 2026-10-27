package io.github.deshark42.modpackupdater.security;

import io.github.deshark42.modpackupdater.Main;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.PathGuard;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.SafePath;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Separates release payload paths from the code that enforces the update policy. */
public final class UpdaterProtection {

    static final int MAX_PATH_WORKERS = 8;
    static final int MIN_PATHS_PER_WORKER = 128;

    private final Path workspace;
    private final Path realWorkspace;
    private final Path updater;

    public UpdaterProtection(Path workspace, Path updater) {
        this.workspace = workspace.toAbsolutePath().normalize();
        try {
            this.realWorkspace = this.workspace.toRealPath();
            this.updater = updater.toRealPath();
        } catch (IOException e) {
            throw new UserError("cannot locate the running updater or workspace for payload protection", e);
        }
    }

    /** Uses the loaded entry point, not a filename convention or a release-supplied setting. */
    public static Path runningUpdater() {
        try {
            CodeSource source = Main.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null
                    || !"file".equalsIgnoreCase(source.getLocation().getProtocol())) {
                throw new UserError("cannot locate the running updater for payload protection");
            }
            return Path.of(source.getLocation().toURI());
        } catch (URISyntaxException | IllegalArgumentException | SecurityException e) {
            throw new UserError("cannot locate the running updater for payload protection", e);
        }
    }

    public void require(Manifest manifest) {
        require(manifest, 1);
    }

    public void require(Manifest manifest, int concurrency) {
        Map<String, Path> paths = new LinkedHashMap<>();
        for (String relative : manifest.files().keySet()) {
            paths.put(relative, workspace.resolve(SafePath.require(relative)).normalize());
        }
        PathGuard guard = PathGuard.at(workspace);
        checkBatches(new ArrayList<>(paths.entrySet()), concurrency, batch -> require(guard, batch));
    }

    private void require(PathGuard guard, List<Map.Entry<String, Path>> paths) {
        Map<Path, BasicFileAttributes> checked = guard.inspectAll(paths.stream().map(Map.Entry::getValue).toList());
        BasicFileAttributes updaterAttributes = null;
        for (Map.Entry<String, Path> entry : paths) {
            String relative = entry.getKey();
            Path file = entry.getValue();
            Path realFile = realWorkspace.resolve(workspace.relativize(file.toAbsolutePath().normalize()));
            // Moving an ancestor moves the updater too; a classes directory is itself executable code.
            if (realFile.startsWith(updater) || updater.startsWith(realFile)) {
                throw conflict(relative);
            }
            BasicFileAttributes attributes = checked.get(file);
            if (attributes == null) {
                continue;
            }
            try {
                if (updaterAttributes == null) {
                    updaterAttributes = Files.readAttributes(updater, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                }
                if (mightBeSameFile(attributes, updaterAttributes) && Files.isSameFile(file, updater)) {
                    throw conflict(relative);
                }
            } catch (IOException e) {
                throw new UserError("cannot check release path against the running updater: " + relative, e);
            }
        }
    }

    /** Keeps each metadata cache worker-local and joins all readers before returning, even on failure. */
    static <T> void checkBatches(List<T> items, int concurrency, Consumer<List<T>> check) {
        int workers = Math.min(Math.min(MAX_PATH_WORKERS, concurrency), items.size() / MIN_PATHS_PER_WORKER);
        if (workers <= 1) {
            check.accept(items);
            return;
        }
        List<T> snapshot = List.copyOf(items);
        ExecutorService pool = Executors.newFixedThreadPool(workers, action -> {
            Thread thread = new Thread(action, "modpack-updater-path-check");
            thread.setDaemon(true);
            return thread;
        });
        boolean interrupted = false;
        try {
            List<Future<?>> running = new ArrayList<>(workers);
            for (int worker = 0; worker < workers; worker++) {
                List<T> batch = snapshot.subList(worker * snapshot.size() / workers,
                        (worker + 1) * snapshot.size() / workers);
                running.add(pool.submit(() -> check.accept(batch)));
            }
            pool.shutdown();
            for (Future<?> task : running) {
                try {
                    task.get();
                } catch (InterruptedException e) {
                    interrupted = true;
                    throw new UserError("interrupted");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof UserError userError) {
                        throw userError;
                    }
                    throw new UserError(cause == null ? e.toString() : cause.toString(), cause);
                }
            }
        } finally {
            pool.shutdownNow();
            while (!pool.isTerminated()) {
                try {
                    pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                    pool.shutdownNow();
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean mightBeSameFile(BasicFileAttributes file, BasicFileAttributes updater) {
        return !(file.isRegularFile() && updater.isDirectory()
                || file.isDirectory() && updater.isRegularFile());
    }

    private static UserError conflict(String relative) {
        return new UserError("release path conflicts with the running updater: " + relative);
    }
}

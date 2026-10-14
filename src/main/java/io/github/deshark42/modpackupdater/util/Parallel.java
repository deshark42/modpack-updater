package io.github.deshark42.modpackupdater.util;

import io.github.deshark42.modpackupdater.UserError;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Runs one action over many items a few at a time. Publishing and updating spend their time
 * waiting on the network, so the commands hand every blob to this instead of taking turns.
 */
public final class Parallel {

    private Parallel() {
    }

    /** Applies {@code action} to every item, at most {@code workers} at a time, and rethrows the first failure. */
    public static <T> void forEach(Collection<T> items, int workers, Consumer<T> action) {
        if (workers <= 1 || items.size() <= 1) {
            for (T item : items) {
                action.accept(item);
            }
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(workers, items.size()), runnable -> {
            Thread thread = new Thread(runnable, "modpack-updater");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<?>> running = new ArrayList<>(items.size());
            for (T item : items) {
                running.add(pool.submit(() -> {
                    action.accept(item);
                    return null;
                }));
            }
            pool.shutdown();
            for (Future<?> future : running) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
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
        }
    }
}

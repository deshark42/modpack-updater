package io.github.deshark42.modpackupdater.util;

import io.github.deshark42.modpackupdater.UserError;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runs one action over many items a few at a time. Publishing and updating spend their time
 * waiting on the network, so the commands hand every blob to this instead of taking turns.
 */
public final class Parallel {

    private Parallel() {
    }

    /** Applies the action with bounded concurrency. A failure stops assigning items and interrupts other workers. */
    public static <T> void forEach(Collection<T> items, int workers, Consumer<T> action) {
        if (workers <= 1 || items.size() <= 1) {
            items.forEach(action);
            return;
        }
        int count = Math.min(workers, items.size());
        Iterator<T> remaining = items.iterator();
        AtomicBoolean stopped = new AtomicBoolean();
        Runnable work = () -> drain(remaining, stopped, action);
        ExecutorService pool = Executors.newFixedThreadPool(count, runnable -> {
            Thread thread = new Thread(runnable, "modpack-updater");
            thread.setDaemon(true);
            return thread;
        });
        CompletionService<Void> completed = new ExecutorCompletionService<>(pool);
        try {
            for (int i = 0; i < count; i++) {
                completed.submit(work, null);
            }
            for (int i = 0; i < count; i++) {
                completed.take().get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UserError("interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UserError userError) {
                throw userError;
            }
            throw new UserError(cause.toString(), cause);
        } finally {
            stopped.set(true);
            pool.shutdownNow();
        }
    }

    private static <T> void drain(Iterator<T> remaining, AtomicBoolean stopped, Consumer<T> action) {
        try {
            while (true) {
                T item;
                synchronized (remaining) {
                    if (stopped.get() || !remaining.hasNext()) {
                        return;
                    }
                    item = remaining.next();
                }
                action.accept(item);
            }
        } catch (RuntimeException | Error failure) {
            stopped.set(true);
            throw failure;
        }
    }
}

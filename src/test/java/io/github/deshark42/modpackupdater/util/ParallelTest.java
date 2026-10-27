package io.github.deshark42.modpackupdater.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ParallelTest {

    @Test
    void appliesTheActionToEveryItemInOrderWhenOnlyOneWorkerIsAskedFor() {
        List<Integer> seen = new ArrayList<>();
        Parallel.forEach(List.of(1, 2, 3), 1, seen::add);
        assertEquals(List.of(1, 2, 3), seen);
    }

    @Test
    void runsItemsAtTheSameTime() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        List<Boolean> together = Collections.synchronizedList(new ArrayList<>());

        Parallel.forEach(List.of(1, 2), 2, item -> {
            bothStarted.countDown();
            together.add(await(bothStarted));
        });

        assertEquals(List.of(true, true), together);
    }

    @Test
    void runsEveryItemWhenThereAreMoreWorkersThanItems() {
        List<Integer> seen = Collections.synchronizedList(new ArrayList<>());

        Parallel.forEach(List.of(1, 2, 3), 8, seen::add);

        assertEquals(List.of(1, 2, 3), seen.stream().sorted().toList());
    }

    @Test
    void rethrowsTheFailureOfTheAction() {
        UserError original = new UserError("the action cannot run");

        UserError error = assertThrows(UserError.class, () -> Parallel.forEach(List.of("a", "b"), 2, item -> {
            throw original;
        }));

        assertSame(original, error);
        assertEquals("the action cannot run", error.getMessage());
    }

    @Test
    void wrapsAFailureThatIsNotAUserError() {
        UserError error = assertThrows(UserError.class, () -> Parallel.forEach(List.of("a", "b"), 2, item -> {
            throw new IllegalStateException("worker broke on " + item);
        }));

        assertEquals("java.lang.IllegalStateException: worker broke on a", error.getMessage());
        assertTrue(error.getCause() instanceof IllegalStateException, String.valueOf(error.getCause()));
        assertEquals("worker broke on a", error.getCause().getMessage());
    }

    @Test
    void aFailedActionInterruptsWorkThatHasNotFinished() {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerStopped = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        List<String> completed = Collections.synchronizedList(new ArrayList<>());
        UserError original = new UserError("the first action failed");

        UserError error = assertThrows(UserError.class, () -> Parallel.forEach(List.of("fails", "waits"), 2, item -> {
            if (item.equals("fails")) {
                assertTrue(await(workerStarted), "the waiting action starts before failure is injected");
                throw original;
            }
            workerStarted.countDown();
            try {
                if (neverReleased.await(5, TimeUnit.SECONDS)) {
                    completed.add(item);
                } else {
                    throw new AssertionError("the waiting worker was not cancelled");
                }
            } catch (InterruptedException expected) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                workerStopped.countDown();
            }
        }));

        assertSame(original, error);
        assertEquals("the first action failed", error.getMessage());
        assertTrue(await(workerStopped), "the cancelled worker terminates");
        assertTrue(interrupted.get(), "unfinished work receives cancellation, rather than merely timing out");
        assertEquals(List.of(), completed, "cancelled work does not commit its side effect");
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("waiting for a worker was interrupted", e);
        }
    }
}

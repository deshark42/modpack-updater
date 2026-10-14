package io.github.deshark42.modpackupdater.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    void rethrowsTheFailureOfTheAction() {
        UserError error = assertThrows(UserError.class, () -> Parallel.forEach(List.of("a", "b"), 2, item -> {
            throw new UserError("no " + item);
        }));
        assertTrue(error.getMessage().startsWith("no "), error.getMessage());
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

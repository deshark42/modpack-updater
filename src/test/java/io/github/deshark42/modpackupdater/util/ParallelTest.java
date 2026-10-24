package io.github.deshark42.modpackupdater.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    void runsEveryItemWhenThereAreMoreWorkersThanItems() {
        List<Integer> seen = Collections.synchronizedList(new ArrayList<>());

        Parallel.forEach(List.of(1, 2, 3), 8, seen::add);

        assertEquals(Set.of(1, 2, 3), new HashSet<>(seen));
    }

    @Test
    void rethrowsTheFailureOfTheAction() {
        UserError error = assertThrows(UserError.class, () -> Parallel.forEach(List.of("a", "b"), 2, item -> {
            throw new UserError("no " + item);
        }));
        assertTrue(error.getMessage().startsWith("no "), error.getMessage());
    }

    @Test
    void wrapsAFailureThatIsNotAUserError() {
        UserError error = assertThrows(UserError.class, () -> Parallel.forEach(List.of("a", "b"), 2, item -> {
            throw new IllegalStateException("worker broke on " + item);
        }));

        assertTrue(error.getMessage().contains("worker broke on"), error.getMessage());
        assertTrue(error.getCause() instanceof IllegalStateException, String.valueOf(error.getCause()));
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

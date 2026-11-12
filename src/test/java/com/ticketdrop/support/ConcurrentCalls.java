package com.ticketdrop.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs many calls at the same instant, which is what a race test needs. Each task gets its own
 * thread; all threads are lined up first and then released together, so the calls genuinely overlap
 * instead of trickling out as the threads start.
 */
public final class ConcurrentCalls {

    private ConcurrentCalls() {
    }

    /**
     * Runs every task at once and returns the results in task order.
     *
     * @throws AssertionError if the tasks have not all finished within {@code timeout} (this is how a
     *                        deadlock or a lock pile-up shows), or if any task throws
     */
    public static <T> List<T> runAll(List<Callable<T>> tasks, Duration timeout) {
        int count = tasks.size();
        CountDownLatch allThreadsReady = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(count);
        try {
            List<Future<T>> futures = new ArrayList<>(count);
            for (Callable<T> task : tasks) {
                futures.add(threads.submit(() -> {
                    allThreadsReady.countDown();
                    go.await();
                    return task.call();
                }));
            }

            long deadline = System.nanoTime() + timeout.toNanos();
            if (!allThreadsReady.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new AssertionError("Could not start " + count + " threads within " + timeout);
            }
            go.countDown();

            List<T> results = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                long remaining = Math.max(deadline - System.nanoTime(), 0);
                try {
                    results.add(futures.get(index).get(remaining, TimeUnit.NANOSECONDS));
                } catch (TimeoutException e) {
                    throw new AssertionError(count + " concurrent calls did not all finish within " + timeout
                            + " (call " + index + " was still running)");
                } catch (ExecutionException e) {
                    throw new AssertionError("Concurrent call " + index + " threw " + e.getCause(), e.getCause());
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while running concurrent calls", e);
        } finally {
            threads.shutdownNow();
        }
    }
}

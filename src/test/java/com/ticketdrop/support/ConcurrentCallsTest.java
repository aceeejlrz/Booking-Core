package com.ticketdrop.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class ConcurrentCallsTest {
    @Test void returnsResultsInTaskOrder() {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) { int n = i; tasks.add(() -> n); }
        assertThat(ConcurrentCalls.runAll(tasks, Duration.ofSeconds(5)))
            .containsExactlyElementsOf(IntStream.range(0, 50).boxed().toList());
    }

    @Test void allTasksRunAtTheSameTime() {
        CyclicBarrier barrier = new CyclicBarrier(100);   // passes only if 100 tasks wait together
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) tasks.add(() -> { barrier.await(5, TimeUnit.SECONDS); return true; });
        assertThat(ConcurrentCalls.runAll(tasks, Duration.ofSeconds(10))).hasSize(100).containsOnly(true);
    }

    @Test void failsWhenTasksDoNotFinishInTime() {
        List<Callable<Boolean>> tasks = List.of(() -> { Thread.sleep(5_000); return true; });
        assertThatThrownBy(() -> ConcurrentCalls.runAll(tasks, Duration.ofMillis(200)))
            .isInstanceOf(AssertionError.class);
    }

    @Test void failsWhenATaskThrows() {
        List<Callable<Boolean>> tasks = List.of(() -> true, () -> { throw new IllegalStateException("boom"); });
        assertThatThrownBy(() -> ConcurrentCalls.runAll(tasks, Duration.ofSeconds(5)))
            .isInstanceOf(AssertionError.class).hasMessageContaining("1");
    }
}

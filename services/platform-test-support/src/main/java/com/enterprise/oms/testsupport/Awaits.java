package com.enterprise.oms.testsupport;

import org.awaitility.Awaitility;
import org.awaitility.core.ThrowingRunnable;

import java.time.Duration;
import java.util.concurrent.Callable;

/** Awaitility shortcuts with sane defaults for async (Kafka/scheduler) assertions. */
public final class Awaits {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private Awaits() {
    }

    public static void until(Callable<Boolean> condition) {
        until(DEFAULT_TIMEOUT, condition);
    }

    public static void until(Duration timeout, Callable<Boolean> condition) {
        Awaitility.await().atMost(timeout).pollInterval(POLL_INTERVAL).until(condition);
    }

    /** Retries the assertions until they pass or the timeout expires (then the last assertion error is thrown). */
    public static void untilAsserted(ThrowingRunnable assertions) {
        untilAsserted(DEFAULT_TIMEOUT, assertions);
    }

    public static void untilAsserted(Duration timeout, ThrowingRunnable assertions) {
        Awaitility.await().atMost(timeout).pollInterval(POLL_INTERVAL).untilAsserted(assertions);
    }

    /** Asserts that the condition stays false for the whole duration (e.g. "no message arrives"). */
    public static void neverWithin(Duration duration, Callable<Boolean> condition) {
        Awaitility.await().during(duration).atMost(duration.plusSeconds(5)).pollInterval(POLL_INTERVAL)
                .until(() -> !condition.call());
    }
}

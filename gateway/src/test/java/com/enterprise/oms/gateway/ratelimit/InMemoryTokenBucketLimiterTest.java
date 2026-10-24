package com.enterprise.oms.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTokenBucketLimiterTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    private InMemoryTokenBucketLimiter limiter(int capacity, double refill, int maxKeys) {
        return new InMemoryTokenBucketLimiter(capacity, refill, maxKeys, nanos::get);
    }

    @Test
    void allowsBurstThenRejectsWithRetryAfter() {
        InMemoryTokenBucketLimiter l = limiter(3, 1.0, 100);
        for (int i = 0; i < 3; i++) {
            assertThat(l.tryAcquire("k").allowed()).isTrue();
        }
        EdgeRateLimiter.Decision d = l.tryAcquire("k");
        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfterSeconds()).isEqualTo(1);
        assertThat(d.remaining()).isZero();
    }

    @Test
    void refillsOverTimeButNeverAboveCapacity() {
        InMemoryTokenBucketLimiter l = limiter(2, 1.0, 100);
        l.tryAcquire("k");
        l.tryAcquire("k");
        assertThat(l.tryAcquire("k").allowed()).isFalse();
        nanos.addAndGet(1_000_000_000L);
        assertThat(l.tryAcquire("k").allowed()).isTrue();
        assertThat(l.tryAcquire("k").allowed()).isFalse();
        nanos.addAndGet(3_600_000_000_000L); // an hour later: capped at capacity
        assertThat(l.tryAcquire("k").remaining()).isEqualTo(1);
        assertThat(l.tryAcquire("k").allowed()).isTrue();
        assertThat(l.tryAcquire("k").allowed()).isFalse();
    }

    @Test
    void keysAreIndependent() {
        InMemoryTokenBucketLimiter l = limiter(1, 0.1, 100);
        assertThat(l.tryAcquire("a").allowed()).isTrue();
        assertThat(l.tryAcquire("a").allowed()).isFalse();
        assertThat(l.tryAcquire("b").allowed()).isTrue();
        assertThat(l.tryAcquire("a").retryAfterSeconds()).isEqualTo(10);
    }

    @Test
    void memoryIsBoundedByPurgingIdleBucketsThenSharingAnOverflowBucket() {
        InMemoryTokenBucketLimiter l = limiter(1, 1.0, 3);
        l.tryAcquire("a");
        l.tryAcquire("b");
        l.tryAcquire("c");
        // map full of busy buckets: a new key falls into the shared overflow bucket instead of growing the map
        assertThat(l.tryAcquire("d").allowed()).isTrue();
        assertThat(l.tryAcquire("e").allowed()).isFalse(); // overflow bucket already used
        assertThat(l.trackedKeys()).isLessThanOrEqualTo(4);
        // after a long idle time the purge frees space and new keys get their own buckets again
        nanos.addAndGet(120_000_000_000L);
        assertThat(l.tryAcquire("f").allowed()).isTrue();
        assertThat(l.trackedKeys()).isLessThanOrEqualTo(3);
    }

    @Test
    void rejectsInvalidConfiguration() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new InMemoryTokenBucketLimiter(0, 1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new InMemoryTokenBucketLimiter(1, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

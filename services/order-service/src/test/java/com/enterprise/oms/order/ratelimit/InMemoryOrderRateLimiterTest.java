package com.enterprise.oms.order.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryOrderRateLimiterTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void allowsTheBurstThenDenies() {
        AtomicLong clock = new AtomicLong();
        InMemoryOrderRateLimiter limiter = new InMemoryOrderRateLimiter(3, 1.0, clock::get);

        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        OrderRateLimiter.Decision denied = limiter.tryAcquire("a");

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void refillsAtTheSustainedRate() {
        AtomicLong clock = new AtomicLong();
        InMemoryOrderRateLimiter limiter = new InMemoryOrderRateLimiter(2, 2.0, clock::get);
        limiter.tryAcquire("a");
        limiter.tryAcquire("a");
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();

        clock.addAndGet(SECOND / 2);   // 0.5s x 2 tokens/s = 1 token
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();

        clock.addAndGet(10 * SECOND);  // never exceeds the capacity
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void retryAfterReflectsTheTimeUntilTheNextToken() {
        AtomicLong clock = new AtomicLong();
        InMemoryOrderRateLimiter limiter = new InMemoryOrderRateLimiter(1, 0.1, clock::get);   // 1 token per 10s
        limiter.tryAcquire("a");

        assertThat(limiter.tryAcquire("a").retryAfterSeconds()).isBetween(10L, 11L);   // ceil of 10.0 (+ fp noise)
        clock.addAndGet(4 * SECOND);
        assertThat(limiter.tryAcquire("a").retryAfterSeconds()).isBetween(6L, 7L);
    }

    @Test
    void callersAreIndependent() {
        AtomicLong clock = new AtomicLong();
        InMemoryOrderRateLimiter limiter = new InMemoryOrderRateLimiter(1, 0.001, clock::get);
        assertThat(limiter.tryAcquire("alice").allowed()).isTrue();
        assertThat(limiter.tryAcquire("alice").allowed()).isFalse();
        assertThat(limiter.tryAcquire("bob").allowed()).isTrue();
    }

    @Test
    void neverGrantsMoreThanCapacityUnderContention() throws Exception {
        InMemoryOrderRateLimiter limiter = new InMemoryOrderRateLimiter(50, 0.0001);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    int granted = 0;
                    for (int i = 0; i < 100; i++) {
                        if (limiter.tryAcquire("shared").allowed()) {
                            granted++;
                        }
                    }
                    return granted;
                }));
            }
            go.countDown();
            int total = 0;
            for (Future<Integer> f : futures) {
                total += f.get(30, TimeUnit.SECONDS);
            }
            assertThat(total).isEqualTo(50);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void idleFullBucketsAreEvictedSoTheMapCannotGrowForever() {
        AtomicLong clock = new AtomicLong();
        InMemoryOrderRateLimiter limiter = new InMemoryOrderRateLimiter(1, 1000.0, clock::get);
        for (int i = 0; i < 10_001; i++) {
            limiter.tryAcquire("k" + i);
        }
        clock.addAndGet(60 * SECOND);          // everything has refilled: idle and full
        limiter.tryAcquire("trigger");         // size > threshold: eviction pass
        assertThat(limiter.trackedKeys()).isLessThan(10);
    }

    @Test
    void rejectsNonsenseConfiguration() {
        assertThatThrownBy(() -> new InMemoryOrderRateLimiter(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemoryOrderRateLimiter(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}

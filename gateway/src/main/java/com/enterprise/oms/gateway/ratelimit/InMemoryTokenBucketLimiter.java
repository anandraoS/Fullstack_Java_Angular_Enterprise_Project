package com.enterprise.oms.gateway.ratelimit;

import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Simple per-key token bucket held in memory (per gateway instance: with N replicas the effective limit is N x capacity).
 * Memory is bounded: when {@code maxKeys} is reached, idle (full) buckets are purged; if the map is still full, new keys share
 * a single overflow bucket instead of growing the map.
 */
public class InMemoryTokenBucketLimiter implements EdgeRateLimiter {

    static final String OVERFLOW_KEY = "__overflow__";
    private static final long IDLE_NANOS = 60_000_000_000L;

    private final int capacity;
    private final double refillPerSecond;
    private final int maxKeys;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong purgeCounter = new AtomicLong();

    public InMemoryTokenBucketLimiter(int capacity, double refillPerSecond, int maxKeys) {
        this(capacity, refillPerSecond, maxKeys, System::nanoTime);
    }

    public InMemoryTokenBucketLimiter(int capacity, double refillPerSecond, int maxKeys, LongSupplier nanoClock) {
        if (capacity < 1 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("capacity must be >= 1 and refillPerSecond > 0");
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.maxKeys = maxKeys;
        this.nanoClock = nanoClock;
    }

    @Override
    public Mono<Decision> acquire(String key) {
        return Mono.just(tryAcquire(key));
    }

    public Decision tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        if (!buckets.containsKey(key) && buckets.size() >= maxKeys) {
            purgeIdle(now);
            if (buckets.size() >= maxKeys) {
                key = OVERFLOW_KEY;
            }
        } else if (purgeCounter.incrementAndGet() % 10_000 == 0) {
            purgeIdle(now);
        }
        return buckets.computeIfAbsent(key, k -> new Bucket(capacity, now)).take(now);
    }

    public int trackedKeys() {
        return buckets.size();
    }

    private void purgeIdle(long now) {
        buckets.entrySet().removeIf(e -> e.getValue().idle(now));
    }

    private final class Bucket {
        private double tokens;
        private long lastNanos;

        Bucket(int initial, long now) {
            this.tokens = initial;
            this.lastNanos = now;
        }

        synchronized Decision take(long now) {
            double elapsedSeconds = Math.max(0, now - lastNanos) / 1_000_000_000.0;
            tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
            lastNanos = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return new Decision(true, (long) Math.floor(tokens), 0);
            }
            long retry = (long) Math.ceil((1.0 - tokens) / refillPerSecond);
            return new Decision(false, 0, Math.max(1, retry));
        }

        synchronized boolean idle(long now) {
            return now - lastNanos > IDLE_NANOS && tokens + ((now - lastNanos) / 1e9) * refillPerSecond >= capacity;
        }
    }
}

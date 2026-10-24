package com.enterprise.oms.order.ratelimit;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Token bucket per key held in this JVM. Capacity = burst, refill = sustained rate. Idle full buckets are evicted
 * lazily so the map cannot grow without bound. Limits are per instance: with N instances the effective limit is
 * N x capacity (use the Redis implementation for a shared limit).
 */
public class InMemoryOrderRateLimiter implements OrderRateLimiter {

    private static final int EVICT_THRESHOLD = 10_000;

    private final int capacity;
    private final double refillPerNano;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public InMemoryOrderRateLimiter(int capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, System::nanoTime);
    }

    /** Test constructor with an injectable clock. */
    public InMemoryOrderRateLimiter(int capacity, double refillPerSecond, LongSupplier nanoClock) {
        if (capacity < 1 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("capacity must be >= 1 and refill > 0");
        }
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1_000_000_000.0;
        this.nanoClock = nanoClock;
    }

    @Override
    public Decision tryAcquire(String key) {
        if (buckets.size() > EVICT_THRESHOLD) {
            evictIdle();
        }
        return buckets.computeIfAbsent(key, k -> new Bucket(capacity, nanoClock.getAsLong())).take();
    }

    int trackedKeys() {
        return buckets.size();
    }

    private void evictIdle() {
        long now = nanoClock.getAsLong();
        for (Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().isFull(now)) {
                it.remove();
            }
        }
    }

    private final class Bucket {
        private double tokens;
        private long lastNanos;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastNanos = now;
        }

        synchronized Decision take() {
            refill(nanoClock.getAsLong());
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return Decision.permit();
            }
            double missing = 1.0 - tokens;
            return Decision.deny((long) Math.ceil(missing / (refillPerNano * 1_000_000_000.0)));
        }

        synchronized boolean isFull(long now) {
            refill(now);
            return tokens >= capacity;
        }

        private void refill(long now) {
            long elapsed = now - lastNanos;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
                lastNanos = now;
            }
        }
    }
}

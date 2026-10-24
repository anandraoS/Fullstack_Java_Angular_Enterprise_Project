package com.enterprise.oms.order.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Shared limiter backed by Redis (enabled only with {@code oms.redis.enabled=true}): fixed window counter
 * ({@code INCR} + {@code EXPIRE}) of {@code limit} requests per {@code window}, identical across all instances.
 * If Redis is unreachable it DEGRADES to the in-memory limiter (fail-open per instance rather than refusing
 * every order because a cache is down) and logs a warning.
 */
public class RedisOrderRateLimiter implements OrderRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisOrderRateLimiter.class);

    private final StringRedisTemplate redis;
    private final int limit;
    private final Duration window;
    private final OrderRateLimiter fallback;
    private final LongSupplier epochSeconds;

    public RedisOrderRateLimiter(StringRedisTemplate redis, int limit, Duration window, OrderRateLimiter fallback) {
        this(redis, limit, window, fallback, () -> System.currentTimeMillis() / 1000);
    }

    RedisOrderRateLimiter(StringRedisTemplate redis, int limit, Duration window, OrderRateLimiter fallback,
                          LongSupplier epochSeconds) {
        this.redis = redis;
        this.limit = limit;
        this.window = window;
        this.fallback = fallback;
        this.epochSeconds = epochSeconds;
    }

    @Override
    public Decision tryAcquire(String key) {
        long windowSeconds = Math.max(1, window.toSeconds());
        long now = epochSeconds.getAsLong();
        long windowStart = now - (now % windowSeconds);
        String redisKey = "oms:ratelimit:orders:" + key + ":" + windowStart;
        try {
            Long count = redis.opsForValue().increment(redisKey);
            if (count == null) {
                return fallback.tryAcquire(key);
            }
            if (count == 1L) {
                redis.expire(redisKey, window.plusSeconds(1));
            }
            if (count <= limit) {
                return Decision.permit();
            }
            return Decision.deny(windowStart + windowSeconds - now);
        } catch (RuntimeException e) {
            log.warn("Redis rate limiter unavailable ({}), using the in-memory limiter", e.getClass().getSimpleName());
            return fallback.tryAcquire(key);
        }
    }
}

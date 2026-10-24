package com.enterprise.oms.order.ratelimit;

/**
 * Rate limiter for {@code POST /orders}. Two implementations: {@link InMemoryOrderRateLimiter} (default; per
 * instance) and {@link RedisOrderRateLimiter} (shared across instances; only when {@code oms.redis.enabled=true}).
 */
public interface OrderRateLimiter {

    /**
     * @param key caller identity (JWT subject)
     * @return the decision; when denied, {@link Decision#retryAfterSeconds()} is a hint for the Retry-After header
     */
    Decision tryAcquire(String key);

    record Decision(boolean allowed, long retryAfterSeconds) {
        public static Decision permit() {
            return new Decision(true, 0);
        }

        public static Decision deny(long retryAfterSeconds) {
            return new Decision(false, Math.max(1, retryAfterSeconds));
        }
    }
}

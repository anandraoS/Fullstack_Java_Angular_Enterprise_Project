package com.enterprise.oms.order.ratelimit;

import com.enterprise.oms.common.error.RateLimitedException;

/** 429 RATE_LIMITED carrying the Retry-After hint (rendered by {@link RateLimitAdvice}). */
public class OrderRateLimitedException extends RateLimitedException {

    private final long retryAfterSeconds;

    public OrderRateLimitedException(long retryAfterSeconds) {
        super("Too many order requests; retry after " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

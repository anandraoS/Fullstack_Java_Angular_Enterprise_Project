package com.enterprise.oms.gateway.ratelimit;

import reactor.core.publisher.Mono;

/** Token-bucket style limiter abstraction (in-memory by default, Redis-backed when {@code oms.redis.enabled=true}). */
public interface EdgeRateLimiter {

    Mono<Decision> acquire(String key);

    /**
     * @param allowed           the request may proceed
     * @param remaining         whole tokens left (informational, never negative)
     * @param retryAfterSeconds seconds until a token is available (>= 1 when not allowed)
     */
    record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
    }
}

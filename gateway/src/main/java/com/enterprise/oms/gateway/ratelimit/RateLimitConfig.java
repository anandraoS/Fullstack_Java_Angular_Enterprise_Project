package com.enterprise.oms.gateway.ratelimit;

import com.enterprise.oms.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Chooses the limiter. Default (and whenever {@code oms.redis.enabled=false}): in-memory token bucket. With
 * {@code oms.redis.enabled=true} Spring Cloud Gateway's {@link RedisRateLimiter} (shared across replicas) is used and any
 * Redis error degrades to the in-memory limiter for that request (fail-open to local limiting, never fail the request).
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);
    static final String ROUTE_ID = "oms-edge";

    @Bean
    EdgeRateLimiter edgeRateLimiter(GatewayProperties props, @Value("${oms.redis.enabled:false}") boolean redisEnabled,
                                    ObjectProvider<RedisRateLimiter> redis) {
        GatewayProperties.RateLimit rl = props.getRateLimit();
        InMemoryTokenBucketLimiter local = new InMemoryTokenBucketLimiter(rl.getCapacity(), rl.getRefillPerSecond(),
                rl.getMaxKeys());
        RedisRateLimiter redisLimiter = redisEnabled ? redis.getIfAvailable() : null;
        if (!redisEnabled) {
            return local;
        }
        if (redisLimiter == null) {
            log.warn("oms.redis.enabled=true but no reactive Redis rate limiter is available; using in-memory limiter");
            return local;
        }
        redisLimiter.getConfig().put(ROUTE_ID, new RedisRateLimiter.Config()
                .setReplenishRate(Math.max(1, (int) Math.round(rl.getRefillPerSecond())))
                .setBurstCapacity(rl.getCapacity())
                .setRequestedTokens(1));
        long retryAfter = Math.max(1, (long) Math.ceil(1.0 / rl.getRefillPerSecond()));
        log.info("Rate limiting backed by Redis (degrades to in-memory on Redis errors)");
        return key -> redisLimiter.isAllowed(ROUTE_ID, key)
                .map(r -> new EdgeRateLimiter.Decision(r.isAllowed(), parse(r.getHeaders().get("X-RateLimit-Remaining")),
                        r.isAllowed() ? 0 : retryAfter))
                .onErrorResume(e -> {
                    log.warn("Redis rate limiter unavailable ({}); using in-memory limiter", e.getClass().getSimpleName());
                    return local.acquire(key);
                })
                .switchIfEmpty(Mono.defer(() -> local.acquire(key)));
    }

    private static long parse(String v) {
        try {
            return v == null ? 0 : Math.max(0, Long.parseLong(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

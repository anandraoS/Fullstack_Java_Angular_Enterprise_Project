package com.enterprise.oms.order.ratelimit;

import com.enterprise.oms.order.config.RateLimitProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Exactly one {@link OrderRateLimiter} bean:
 * in-memory unless {@code oms.redis.enabled=true} (default false), in which case the Redis limiter is used and
 * an in-memory instance is kept as its fallback.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    @ConditionalOnProperty(prefix = "oms.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
    OrderRateLimiter inMemoryOrderRateLimiter(RateLimitProperties props) {
        return new InMemoryOrderRateLimiter(props.getCapacity(), props.getRefillPerSecond());
    }

    @Bean
    @ConditionalOnProperty(prefix = "oms.redis", name = "enabled", havingValue = "true")
    OrderRateLimiter redisOrderRateLimiter(StringRedisTemplate redis, RateLimitProperties props) {
        long windowSeconds = Math.max(1, Math.round(props.getCapacity() / props.getRefillPerSecond()));
        return new RedisOrderRateLimiter(redis, props.getCapacity(), Duration.ofSeconds(windowSeconds),
                new InMemoryOrderRateLimiter(props.getCapacity(), props.getRefillPerSecond()));
    }
}

package com.enterprise.oms.order.ratelimit;

import com.enterprise.oms.order.config.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Exactly one limiter bean: in-memory by default, Redis only with oms.redis.enabled=true. */
class RateLimitConfigTest {

    @Configuration
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class Base {
        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return mock(StringRedisTemplate.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Base.class, RateLimitConfig.class);

    @Test
    void defaultIsTheInMemoryLimiter() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(OrderRateLimiter.class);
            assertThat(ctx.getBean(OrderRateLimiter.class)).isInstanceOf(InMemoryOrderRateLimiter.class);
        });
    }

    @Test
    void redisFlagFalseKeepsTheInMemoryLimiter() {
        runner.withPropertyValues("oms.redis.enabled=false").run(ctx ->
                assertThat(ctx.getBean(OrderRateLimiter.class)).isInstanceOf(InMemoryOrderRateLimiter.class));
    }

    @Test
    void redisFlagTrueSwitchesToTheRedisLimiter() {
        runner.withPropertyValues("oms.redis.enabled=true", "oms.order.rate-limit.capacity=10",
                "oms.order.rate-limit.refill-per-second=2").run(ctx -> {
            assertThat(ctx).hasSingleBean(OrderRateLimiter.class);
            assertThat(ctx.getBean(OrderRateLimiter.class)).isInstanceOf(RedisOrderRateLimiter.class);
        });
    }

    @Test
    void propertyDefaultsAreTheDocumentedOnes() {
        runner.run(ctx -> {
            RateLimitProperties p = ctx.getBean(RateLimitProperties.class);
            assertThat(p.isEnabled()).isTrue();
            assertThat(p.getCapacity()).isEqualTo(20);
            assertThat(p.getRefillPerSecond()).isEqualTo(5.0);
        });
    }
}

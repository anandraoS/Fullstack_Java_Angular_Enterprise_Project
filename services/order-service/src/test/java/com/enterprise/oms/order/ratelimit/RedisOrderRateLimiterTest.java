package com.enterprise.oms.order.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Redis limiter against a mocked template (no Redis server needed): counting, expiry, fallback. */
class RedisOrderRateLimiterTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private OrderRateLimiter fallback;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void mocks() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        fallback = mock(OrderRateLimiter.class);
        when(redis.opsForValue()).thenReturn(ops);
    }

    private RedisOrderRateLimiter limiter(int limit, long nowSeconds) {
        return new RedisOrderRateLimiter(redis, limit, Duration.ofSeconds(10), fallback, () -> nowSeconds);
    }

    @Test
    void permitsUpToTheLimitThenDeniesWithTheRemainingWindowAsRetryAfter() {
        RedisOrderRateLimiter l = limiter(2, 1_000_003);   // window [1000000, 1000010) -> 7s left
        when(ops.increment(anyString())).thenReturn(1L, 2L, 3L);

        assertThat(l.tryAcquire("alice").allowed()).isTrue();
        assertThat(l.tryAcquire("alice").allowed()).isTrue();
        OrderRateLimiter.Decision denied = l.tryAcquire("alice");

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(7);
    }

    @Test
    void theKeyIsPerCallerAndPerWindowAndExpiresOnlyWhenCreated() {
        RedisOrderRateLimiter l = limiter(5, 1_000_003);
        when(ops.increment(anyString())).thenReturn(1L, 2L);

        l.tryAcquire("alice");
        l.tryAcquire("alice");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(ops, org.mockito.Mockito.times(2)).increment(key.capture());
        assertThat(key.getAllValues()).containsOnly("oms:ratelimit:orders:alice:1000000");
        verify(redis, org.mockito.Mockito.times(1)).expire("oms:ratelimit:orders:alice:1000000", Duration.ofSeconds(11));
    }

    @Test
    void degradesToTheInMemoryLimiterWhenRedisIsDown() {
        RedisOrderRateLimiter l = limiter(5, 1_000_003);
        when(ops.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        when(fallback.tryAcquire("alice")).thenReturn(OrderRateLimiter.Decision.permit());

        assertThat(l.tryAcquire("alice").allowed()).isTrue();
        verify(fallback).tryAcquire("alice");
    }

    @Test
    void aNullCounterAlsoFallsBack() {
        RedisOrderRateLimiter l = limiter(5, 1_000_003);
        when(ops.increment(anyString())).thenReturn(null);
        when(fallback.tryAcquire("bob")).thenReturn(OrderRateLimiter.Decision.deny(3));

        assertThat(l.tryAcquire("bob").allowed()).isFalse();
    }

    @Test
    void doesNotTouchTheFallbackWhileRedisWorks() {
        RedisOrderRateLimiter l = limiter(5, 1_000_003);
        when(ops.increment(anyString())).thenReturn(1L);
        l.tryAcquire("alice");
        verify(fallback, never()).tryAcquire(anyString());
    }
}

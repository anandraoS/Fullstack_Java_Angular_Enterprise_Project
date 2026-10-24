package com.enterprise.oms.inventory.cache;

import com.enterprise.oms.inventory.service.ProductView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-backed implementation (only created when {@code oms.redis.enabled=true}). Values are JSON strings with a TTL;
 * keys are namespaced {@code oms:inventory:product:<id>}. Every Redis error degrades to a cache miss (logged at WARN
 * without the key payload), so a Redis outage costs latency, never correctness.
 */
public class RedisProductCache implements ProductCache {

    private static final Logger log = LoggerFactory.getLogger(RedisProductCache.class);
    private static final String PREFIX = "oms:inventory:product:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public RedisProductCache(StringRedisTemplate redis, ObjectMapper mapper, int ttlSeconds) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    @Override
    public Optional<ProductView> get(UUID productId) {
        try {
            String json = redis.opsForValue().get(PREFIX + productId);
            return json == null ? Optional.empty() : Optional.of(mapper.readValue(json, ProductView.class));
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("Redis product cache read failed, treating as miss: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void put(ProductView view) {
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        try {
            redis.opsForValue().set(PREFIX + view.id(), mapper.writeValueAsString(view), ttl);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("Redis product cache write failed: {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public void evict(UUID productId) {
        try {
            redis.delete(PREFIX + productId);
        } catch (RuntimeException e) {
            log.warn("Redis product cache evict failed (entry expires by TTL): {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public void clear() {
        try {
            var keys = redis.keys(PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (RuntimeException e) {
            log.warn("Redis product cache clear failed: {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public String backend() {
        return "redis";
    }
}

package com.enterprise.oms.inventory.cache;

import com.enterprise.oms.inventory.config.InventoryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;

/** {@code oms.redis.enabled=false} (default): in-memory cache. {@code true}: Redis cache (connection via spring.data.redis.*). */
@Configuration(proxyBeanMethods = false)
public class CacheConfig {

    @Bean
    @ConditionalOnProperty(prefix = "oms.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
    ProductCache inMemoryProductCache(InventoryProperties props, Clock clock) {
        return new InMemoryProductCache(props.getCache().getTtlSeconds(), props.getCache().getMaxEntries(), clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "oms.redis", name = "enabled", havingValue = "true")
    ProductCache redisProductCache(StringRedisTemplate redis, ObjectMapper mapper, InventoryProperties props) {
        return new RedisProductCache(redis, mapper, props.getCache().getTtlSeconds());
    }
}

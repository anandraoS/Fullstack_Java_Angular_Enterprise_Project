package com.enterprise.oms.order.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the REST inventory client by an in-memory stub for every injection of {@code InventoryClient}
 * (the real REST client is tested separately in InventoryClientResilienceTest).
 */
@TestConfiguration
public class TestInventoryConfig {

    @Bean
    @Primary
    StubInventoryClient stubInventoryClient() {
        return new StubInventoryClient();
    }
}

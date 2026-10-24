package com.enterprise.oms.inventory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class InventoryConfig {

    /** UTC clock (injectable so scheduler / TTL logic is unit-testable with a fixed clock). */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}

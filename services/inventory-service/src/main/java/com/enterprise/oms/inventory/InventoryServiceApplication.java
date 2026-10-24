package com.enterprise.oms.inventory;

import com.enterprise.oms.inventory.config.InventoryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Inventory service: product catalog, stock and reservations (contract sections 3, 5.2, 7). */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(InventoryProperties.class)
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}

package com.enterprise.oms.fulfillment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Fulfillment service: consumes StartFulfillment commands and simulates pick/pack/ship (contract 5.3, 7). */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FulfillmentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FulfillmentServiceApplication.class, args);
    }
}

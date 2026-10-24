package com.enterprise.oms.fulfillment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** On startup, resumes fulfillments that were in flight when the previous process stopped (crash recovery). */
@Component
public class FulfillmentRecovery {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentRecovery.class);

    private final FulfillmentService service;

    public FulfillmentRecovery(FulfillmentService service) {
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            service.recoverInFlight();
        } catch (RuntimeException e) {
            log.warn("Fulfillment crash recovery could not run: {}", e.toString());
        }
    }
}

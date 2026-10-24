package com.enterprise.oms.inventory.service;

import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventPayloads.QuantityItem;
import com.enterprise.oms.common.events.EventPayloads.Shortage;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.common.outbox.OutboxService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Publishes inventory events through the transactional outbox (same DB transaction as the stock change; the relay
 * delivers them to {@code oms.inventory.events.v1} with key = orderId). Must be called inside a transaction.
 */
@Component
public class InventoryEventPublisher {

    private final OutboxService outbox;
    private final String producer;

    public InventoryEventPublisher(OutboxService outbox,
                                   @Value("${spring.application.name:inventory-service}") String producer) {
        this.outbox = outbox;
        this.producer = producer;
    }

    public void reserved(UUID orderId, UUID reservationId, List<QuantityItem> items, Instant expiresAt,
                         String correlationId, UUID causationId) {
        append(EventTypes.INVENTORY_RESERVED, orderId, correlationId, causationId,
                new EventPayloads.InventoryReserved(orderId, reservationId, items, expiresAt));
    }

    public void failed(UUID orderId, String reason, List<Shortage> shortages, String correlationId, UUID causationId) {
        append(EventTypes.INVENTORY_RESERVATION_FAILED, orderId, correlationId, causationId,
                new EventPayloads.InventoryReservationFailed(orderId, reason, shortages));
    }

    public void released(UUID orderId, UUID reservationId, String reason, String correlationId, UUID causationId) {
        append(EventTypes.INVENTORY_RELEASED, orderId, correlationId, causationId,
                new EventPayloads.InventoryReleased(orderId, reservationId, reason));
    }

    private void append(String type, UUID orderId, String correlationId, UUID causationId, Object payload) {
        outbox.append(EventEnvelope.forOrder(type, orderId, producer, correlationId, causationId, payload));
    }
}

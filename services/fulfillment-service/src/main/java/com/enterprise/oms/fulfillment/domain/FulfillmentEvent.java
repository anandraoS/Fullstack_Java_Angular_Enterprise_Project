package com.enterprise.oms.fulfillment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Status-change history row of a fulfillment (table fulfillment_events). */
@Entity
@Table(name = "fulfillment_events")
public class FulfillmentEvent {

    @Id
    private UUID id;
    @Column(name = "fulfillment_id", nullable = false)
    private UUID fulfillmentId;
    @Column(nullable = false)
    private int seq;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FulfillmentStatus status;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected FulfillmentEvent() {
    }

    public FulfillmentEvent(UUID id, UUID fulfillmentId, int seq, FulfillmentStatus status, Instant occurredAt) {
        this.id = id;
        this.fulfillmentId = fulfillmentId;
        this.seq = seq;
        this.status = status;
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public UUID getFulfillmentId() { return fulfillmentId; }
    public int getSeq() { return seq; }
    public FulfillmentStatus getStatus() { return status; }
    public Instant getOccurredAt() { return occurredAt; }
}

package com.enterprise.oms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Append-only ledger row. */
@Entity
@Table(name = "stock_movements")
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;
    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, updatable = false)
    private MovementType type;
    @Column(name = "on_hand_delta", nullable = false, updatable = false)
    private int onHandDelta;
    @Column(name = "reserved_delta", nullable = false, updatable = false)
    private int reservedDelta;
    @Column(name = "on_hand_after", updatable = false)
    private Integer onHandAfter;
    @Column(name = "reserved_after", updatable = false)
    private Integer reservedAfter;
    @Column(updatable = false)
    private String reason;
    @Column(updatable = false)
    private String reference;
    @Column(nullable = false, updatable = false)
    private String actor;
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected StockMovement() {
    }

    public StockMovement(UUID productId, MovementType type, int onHandDelta, int reservedDelta, Integer onHandAfter,
                         Integer reservedAfter, String reason, String reference, String actor, Instant occurredAt) {
        this.productId = productId;
        this.type = type;
        this.onHandDelta = onHandDelta;
        this.reservedDelta = reservedDelta;
        this.onHandAfter = onHandAfter;
        this.reservedAfter = reservedAfter;
        this.reason = reason;
        this.reference = reference;
        this.actor = actor;
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public UUID getProductId() { return productId; }
    public MovementType getType() { return type; }
    public int getOnHandDelta() { return onHandDelta; }
    public int getReservedDelta() { return reservedDelta; }
    public Integer getOnHandAfter() { return onHandAfter; }
    public Integer getReservedAfter() { return reservedAfter; }
    public String getReason() { return reason; }
    public String getReference() { return reference; }
    public String getActor() { return actor; }
    public Instant getOccurredAt() { return occurredAt; }
}

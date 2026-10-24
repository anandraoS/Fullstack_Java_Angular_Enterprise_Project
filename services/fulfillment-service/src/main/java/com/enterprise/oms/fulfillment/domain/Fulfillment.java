package com.enterprise.oms.fulfillment.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Aggregate: one fulfillment per order (unique order_id). */
@Entity
@Table(name = "fulfillments")
public class Fulfillment {

    @Id
    private UUID id;
    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FulfillmentStatus status;
    @Column(name = "tracking_number")
    private String trackingNumber;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "failure_reason")
    private String failureReason;
    @Column(name = "ship_line1")
    private String shipLine1;
    @Column(name = "ship_city")
    private String shipCity;
    @Column(name = "ship_postal")
    private String shipPostal;
    @Column(name = "ship_country")
    private String shipCountry;
    @Column(name = "item_count", nullable = false)
    private int itemCount;
    @Column(name = "correlation_id")
    private String correlationId;
    @Column(name = "source_event_id")
    private UUID sourceEventId;
    @Column(name = "next_step_at")
    private Instant nextStepAt;
    @Version
    private Long version;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "fulfillment_id", insertable = false, updatable = false)
    @OrderBy("seq ASC")
    private List<FulfillmentEvent> events = new ArrayList<>();

    protected Fulfillment() {
    }

    public Fulfillment(UUID id, UUID orderId, String line1, String city, String postal, String country, int itemCount,
                       String correlationId, UUID sourceEventId, Instant now) {
        this.id = id;
        this.orderId = orderId;
        this.shipLine1 = line1;
        this.shipCity = city;
        this.shipPostal = postal;
        this.shipCountry = country;
        this.itemCount = itemCount;
        this.correlationId = correlationId;
        this.sourceEventId = sourceEventId;
        this.status = FulfillmentStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
        this.nextStepAt = now;
        this.events.add(new FulfillmentEvent(UUID.randomUUID(), id, 1, FulfillmentStatus.PENDING, now));
    }

    /** Applies a validated transition and records the event. */
    public void transitionTo(FulfillmentStatus to, Instant now) {
        FulfillmentStateMachine.assertTransition(status, to);
        this.status = to;
        this.updatedAt = now;
        this.events.add(new FulfillmentEvent(UUID.randomUUID(), id, events.size() + 1, to, now));
    }

    public void scheduleNextStep(Instant at) {
        this.nextStepAt = at;
    }

    public void clearSchedule() {
        this.nextStepAt = null;
    }

    public int recordAttempt() {
        return ++attempts;
    }

    public void resetAttempts() {
        this.attempts = 0;
    }

    public void setTrackingNumber(String trackingNumber) { this.trackingNumber = trackingNumber; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public void touch(Instant now) { this.updatedAt = now; }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public FulfillmentStatus getStatus() { return status; }
    public String getTrackingNumber() { return trackingNumber; }
    public int getAttempts() { return attempts; }
    public String getFailureReason() { return failureReason; }
    public String getShipLine1() { return shipLine1; }
    public String getShipCity() { return shipCity; }
    public String getShipPostal() { return shipPostal; }
    public String getShipCountry() { return shipCountry; }
    public int getItemCount() { return itemCount; }
    public String getCorrelationId() { return correlationId; }
    public UUID getSourceEventId() { return sourceEventId; }
    public Instant getNextStepAt() { return nextStepAt; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<FulfillmentEvent> getEvents() { return events; }
}

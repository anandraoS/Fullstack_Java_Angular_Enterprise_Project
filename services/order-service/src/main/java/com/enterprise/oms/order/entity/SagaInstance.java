package com.enterprise.oms.order.entity;

import com.enterprise.oms.order.domain.SagaState;
import com.enterprise.oms.order.domain.SagaStep;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** One saga per order (unique order_id). {@code @Version}: concurrent event handlers cannot both advance it. */
@Entity
@Table(name = "saga_instances")
public class SagaInstance {

    @Id
    private UUID id;
    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private SagaState state;
    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", nullable = false, length = 32)
    private SagaStep currentStep;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "last_error", length = 1000)
    private String lastError;
    @Column(length = 32)
    private String reason;
    @Column(name = "reservation_id")
    private UUID reservationId;
    @Column(name = "reservation_released", nullable = false)
    private boolean reservationReleased;
    @Column(name = "fulfillment_started", nullable = false)
    private boolean fulfillmentStarted;
    @Version
    private Long version;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SagaInstance() {
    }

    public SagaInstance(UUID orderId, Instant now) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.state = SagaState.RUNNING;
        this.currentStep = SagaStep.VALIDATE;
        this.startedAt = now;
        this.updatedAt = now;
    }

    /** Moves to a step and resets the retry counter (progress was made). */
    public void advanceTo(SagaStep step, Instant now) {
        this.currentStep = step;
        this.attempts = 0;
        this.lastError = null;
        this.updatedAt = now;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }

    public int incrementAttempts(Instant now) {
        this.updatedAt = now;
        return ++this.attempts;
    }

    public void recordError(String error, Instant now) {
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
        this.updatedAt = now;
    }

    public void setState(SagaState state, Instant now) {
        this.state = state;
        this.updatedAt = now;
    }

    public void startCompensation(String reason, Instant now) {
        this.reason = reason;
        this.state = SagaState.COMPENSATING;
        this.currentStep = SagaStep.RELEASE_INVENTORY;
        this.attempts = 0;
        this.lastError = null;
        this.updatedAt = now;
    }

    public void resetAttempts(Instant now) {
        this.attempts = 0;
        this.updatedAt = now;
    }

    public void setReason(String reason) { this.reason = reason; }
    public void setReservationId(UUID reservationId) { this.reservationId = reservationId; }
    public void setReservationReleased(boolean released) { this.reservationReleased = released; }
    public void setFulfillmentStarted(boolean started) { this.fulfillmentStarted = started; }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public SagaState getState() { return state; }
    public SagaStep getCurrentStep() { return currentStep; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public String getReason() { return reason; }
    public UUID getReservationId() { return reservationId; }
    public boolean isReservationReleased() { return reservationReleased; }
    public boolean isFulfillmentStarted() { return fulfillmentStarted; }
    public Long getVersion() { return version; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

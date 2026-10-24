package com.enterprise.oms.order.entity;

import com.enterprise.oms.order.domain.SagaStep;
import com.enterprise.oms.order.domain.StepStatus;
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

@Entity
@Table(name = "saga_step_log")
public class SagaStepLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "saga_id", nullable = false)
    private UUID sagaId;
    @Column(name = "order_id", nullable = false)
    private UUID orderId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SagaStep step;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StepStatus status;
    @Column(nullable = false)
    private int attempt;
    @Column(length = 1000)
    private String detail;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected SagaStepLog() {
    }

    public SagaStepLog(UUID sagaId, UUID orderId, SagaStep step, StepStatus status, int attempt, String detail,
                       Instant occurredAt) {
        this.sagaId = sagaId;
        this.orderId = orderId;
        this.step = step;
        this.status = status;
        this.attempt = attempt;
        this.detail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 1000));
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public UUID getSagaId() { return sagaId; }
    public UUID getOrderId() { return orderId; }
    public SagaStep getStep() { return step; }
    public StepStatus getStatus() { return status; }
    public int getAttempt() { return attempt; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
}

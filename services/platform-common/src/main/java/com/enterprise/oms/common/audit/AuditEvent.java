package com.enterprise.oms.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Immutable audit trail row (table {@code audit_events}). */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    private UUID id;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Column(nullable = false)
    private String actor;
    @Column(name = "entity_type", nullable = false)
    private String entityType;
    @Column(name = "entity_id", nullable = false)
    private String entityId;
    @Column(nullable = false)
    private String action;
    @Column(length = 8000)
    private String detail;
    @Column(name = "correlation_id")
    private String correlationId;

    protected AuditEvent() {
    }

    public AuditEvent(UUID id, Instant occurredAt, String actor, String entityType, String entityId, String action,
                      String detail, String correlationId) {
        this.id = id;
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.detail = detail;
        this.correlationId = correlationId;
    }

    public UUID getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getActor() { return actor; }
    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
    public String getAction() { return action; }
    public String getDetail() { return detail; }
    public String getCorrelationId() { return correlationId; }
}

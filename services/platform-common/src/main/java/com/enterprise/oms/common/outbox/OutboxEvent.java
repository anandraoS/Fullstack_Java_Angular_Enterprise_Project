package com.enterprise.oms.common.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Row of the transactional outbox (table {@code outbox_events}). Written in the business transaction. */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;
    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;
    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;
    @Column(name = "event_type", nullable = false)
    private String eventType;
    @Column(nullable = false)
    private String topic;
    @Column(name = "event_key")
    private String eventKey;
    @Column(nullable = false, length = 20000)
    private String payload;
    @Column(length = 4000)
    private String headers;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "published_at")
    private Instant publishedAt;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "last_error", length = 1000)
    private String lastError;

    protected OutboxEvent() {
    }

    public OutboxEvent(UUID id, String aggregateType, String aggregateId, String eventType, String topic,
                       String eventKey, String payload, String headers, Instant createdAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.eventKey = eventKey;
        this.payload = payload;
        this.headers = headers;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getTopic() { return topic; }
    public String getEventKey() { return eventKey; }
    public String getPayload() { return payload; }
    public String getHeaders() { return headers; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }

    public void markPublished(Instant at) {
        this.publishedAt = at;
        this.attempts++;
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
    }
}

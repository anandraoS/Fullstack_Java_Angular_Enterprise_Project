package com.enterprise.oms.common.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** {@code processed_events(consumer, event_id)}: PK = unique constraint used for consumer de-duplication. */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "consumer", nullable = false)
        private String consumer;
        @Column(name = "event_id", nullable = false)
        private UUID eventId;

        protected Key() {
        }

        public Key(String consumer, UUID eventId) {
            this.consumer = consumer;
            this.eventId = eventId;
        }

        public String getConsumer() { return consumer; }
        public UUID getEventId() { return eventId; }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(consumer, k.consumer) && Objects.equals(eventId, k.eventId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(consumer, eventId);
        }
    }

    @EmbeddedId
    private Key id;
    @Column(name = "event_type")
    private String eventType;
    private String topic;
    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    public ProcessedEvent(String consumer, UUID eventId, String eventType, String topic, Instant processedAt) {
        this.id = new Key(consumer, eventId);
        this.eventType = eventType;
        this.topic = topic;
        this.processedAt = processedAt;
    }

    public Key getId() { return id; }
    public String getEventType() { return eventType; }
    public String getTopic() { return topic; }
    public Instant getProcessedAt() { return processedAt; }
}

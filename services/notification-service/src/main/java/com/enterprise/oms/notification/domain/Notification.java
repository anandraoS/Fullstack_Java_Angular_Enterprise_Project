package com.enterprise.oms.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** One simulated customer notification. Unique per (source_event_id, template). */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    private UUID id;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
    @Column(name = "order_id", nullable = false)
    private UUID orderId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;
    @Column(nullable = false)
    private String template;
    @Column(nullable = false)
    private String subject;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "last_error")
    private String lastError;
    @Column(name = "correlation_id")
    private String correlationId;
    @Column(name = "source_event_id", nullable = false)
    private UUID sourceEventId;
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;
    @Version
    private Long version;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "sent_at")
    private Instant sentAt;

    protected Notification() {
    }

    public Notification(UUID id, UUID customerId, UUID orderId, NotificationChannel channel,
                        NotificationTemplate template, String subject, String correlationId, UUID sourceEventId,
                        Instant now) {
        this.id = id;
        this.customerId = customerId;
        this.orderId = orderId;
        this.channel = channel;
        this.template = template.name();
        this.subject = subject;
        this.correlationId = correlationId;
        this.sourceEventId = sourceEventId;
        this.status = NotificationStatus.PENDING;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    /** Delivery attempt succeeded. */
    public void markSent(Instant now) {
        status.assertTransitionTo(NotificationStatus.SENT);
        attempts++;
        status = NotificationStatus.SENT;
        sentAt = now;
        lastError = null;
        nextAttemptAt = null;
    }

    /** Delivery attempt failed: RETRYING with the next attempt at {@code retryAt}, or FAILED when {@code exhausted}. */
    public void markAttemptFailed(String error, boolean exhausted, Instant retryAt) {
        NotificationStatus to = exhausted ? NotificationStatus.FAILED : NotificationStatus.RETRYING;
        status.assertTransitionTo(to);
        attempts++;
        status = to;
        lastError = error;
        nextAttemptAt = exhausted ? null : retryAt;
    }

    /** Operator retry: FAILED -> PENDING with a fresh attempt budget. */
    public void requeue(Instant now) {
        status.assertTransitionTo(NotificationStatus.PENDING);
        status = NotificationStatus.PENDING;
        attempts = 0;
        nextAttemptAt = now;
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public UUID getOrderId() { return orderId; }
    public NotificationChannel getChannel() { return channel; }
    public String getTemplate() { return template; }
    public String getSubject() { return subject; }
    public NotificationStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public String getCorrelationId() { return correlationId; }
    public UUID getSourceEventId() { return sourceEventId; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
}

package com.enterprise.oms.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One delivery attempt (table notification_attempts). */
@Entity
@Table(name = "notification_attempts")
public class NotificationAttempt {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";

    @Id
    private UUID id;
    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;
    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;
    @Column(nullable = false)
    private String outcome;
    private String error;
    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    protected NotificationAttempt() {
    }

    public NotificationAttempt(UUID id, UUID notificationId, int attemptNo, String outcome, String error,
                               Instant attemptedAt) {
        this.id = id;
        this.notificationId = notificationId;
        this.attemptNo = attemptNo;
        this.outcome = outcome;
        this.error = error;
        this.attemptedAt = attemptedAt;
    }

    public UUID getId() { return id; }
    public UUID getNotificationId() { return notificationId; }
    public int getAttemptNo() { return attemptNo; }
    public String getOutcome() { return outcome; }
    public String getError() { return error; }
    public Instant getAttemptedAt() { return attemptedAt; }
}

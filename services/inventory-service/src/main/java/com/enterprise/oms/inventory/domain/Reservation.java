package com.enterprise.oms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * One reservation per order (unique {@code order_id}), whatever its outcome. The row is CLAIMED with an
 * {@code INSERT ... ON CONFLICT DO NOTHING} before stock is touched; see {@code ReservationRepository}.
 * State changes after the claim go through conditional UPDATEs (status guards) in the repository, so two
 * concurrent actors (e.g. expiry job vs OrderConfirmed) can never both win.
 */
@Entity
@Table(name = "inventory_reservations")
public class Reservation {

    @Id
    private UUID id;
    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "release_reason")
    private String releaseReason;
    @Column(name = "failure_reason")
    private String failureReason;
    @Column(name = "failure_detail")
    private String failureDetail;
    @Column(name = "shipped_at")
    private Instant shippedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private Long version;

    protected Reservation() {
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public ReservationStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getReleaseReason() { return releaseReason; }
    public String getFailureReason() { return failureReason; }
    public String getFailureDetail() { return failureDetail; }
    public Instant getShippedAt() { return shippedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}

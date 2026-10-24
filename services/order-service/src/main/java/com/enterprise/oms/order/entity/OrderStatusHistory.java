package com.enterprise.oms.order.entity;

import com.enterprise.oms.order.domain.OrderStatus;
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
@Table(name = "order_status_history")
public class OrderStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "order_id", nullable = false)
    private UUID orderId;
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private OrderStatus fromStatus;
    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 32)
    private OrderStatus toStatus;
    @Column(length = 1000)
    private String reason;
    @Column(nullable = false, length = 200)
    private String actor;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected OrderStatusHistory() {
    }

    public OrderStatusHistory(UUID orderId, OrderStatus fromStatus, OrderStatus toStatus, String reason, String actor,
                              Instant occurredAt) {
        this.orderId = orderId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 1000));
        this.actor = actor;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public OrderStatus getFromStatus() { return fromStatus; }
    public OrderStatus getToStatus() { return toStatus; }
    public String getReason() { return reason; }
    public String getActor() { return actor; }
    public Instant getOccurredAt() { return occurredAt; }
}

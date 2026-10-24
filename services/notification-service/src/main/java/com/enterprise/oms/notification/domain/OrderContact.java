package com.enterprise.oms.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Customer identity and e-mail for an order, learned from order events. Needed because fulfillment events carry only
 * the orderId. The address is personal data: it is never returned by the API and only logged masked.
 */
@Entity
@Table(name = "order_contacts")
public class OrderContact {

    @Id
    @Column(name = "order_id")
    private UUID orderId;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
    @Column(name = "customer_email", nullable = false)
    private String customerEmail;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrderContact() {
    }

    public OrderContact(UUID orderId, UUID customerId, String customerEmail, Instant now) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.customerEmail = customerEmail;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(UUID customerId, String customerEmail, Instant now) {
        this.customerId = customerId;
        this.customerEmail = customerEmail;
        this.updatedAt = now;
    }

    public UUID getOrderId() { return orderId; }
    public UUID getCustomerId() { return customerId; }
    public String getCustomerEmail() { return customerEmail; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

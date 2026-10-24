package com.enterprise.oms.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Row of idempotency_keys: unique (scope, idem_key); keeps the request fingerprint and the response snapshot. */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKeyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 64)
    private String scope;
    @Column(name = "idem_key", nullable = false, length = 64)
    private String idemKey;
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;
    @Column(name = "order_id")
    private UUID orderId;
    @Column(name = "response_status")
    private Integer responseStatus;
    @Column(name = "response_body", length = 50000)
    private String responseBody;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyKeyRecord() {
    }

    public IdempotencyKeyRecord(String scope, String key, String requestHash, Instant createdAt) {
        this.scope = scope;
        this.idemKey = key;
        this.requestHash = requestHash;
        this.createdAt = createdAt;
    }

    public void complete(UUID orderId, int status, String body) {
        this.orderId = orderId;
        this.responseStatus = status;
        this.responseBody = body;
    }

    public Long getId() { return id; }
    public String getScope() { return scope; }
    public String getIdemKey() { return idemKey; }
    public String getRequestHash() { return requestHash; }
    public UUID getOrderId() { return orderId; }
    public Integer getResponseStatus() { return responseStatus; }
    public String getResponseBody() { return responseBody; }
    public Instant getCreatedAt() { return createdAt; }
}

package com.enterprise.oms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Stock of one product. {@code available = onHand - reserved}. {@code version} is the optimistic-lock token and is
 * ALSO bumped by the atomic conditional UPDATEs in {@code StockRepository}, so a concurrent reservation makes an
 * admin edit based on an older read fail its version check (and retry).
 */
@Entity
@Table(name = "stock")
public class Stock {

    @Id
    @Column(name = "product_id")
    private UUID productId;
    @Column(name = "on_hand", nullable = false)
    private int onHand;
    @Column(nullable = false)
    private int reserved;
    @Version
    private Long version;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Stock() {
    }

    public Stock(UUID productId, int onHand, Instant now) {
        this.productId = productId;
        this.onHand = onHand;
        this.reserved = 0;
        this.updatedAt = now;
    }

    public int available() {
        return onHand - reserved;
    }

    public void setOnHand(int onHand, Instant now) {
        this.onHand = onHand;
        this.updatedAt = now;
    }

    public void setReserved(int reserved, Instant now) {
        this.reserved = reserved;
        this.updatedAt = now;
    }

    public UUID getProductId() { return productId; }
    public int getOnHand() { return onHand; }
    public int getReserved() { return reserved; }
    public Long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
}

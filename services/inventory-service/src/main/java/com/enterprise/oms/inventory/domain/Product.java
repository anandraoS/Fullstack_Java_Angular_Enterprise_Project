package com.enterprise.oms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Catalog entry. Stock numbers live in {@link Stock} (separate row, separate lock domain). */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false, updatable = false)
    private String sku;
    @Column(nullable = false)
    private String name;
    private String description;
    @Column(nullable = false)
    private String category;
    @Column(nullable = false)
    private BigDecimal price;
    @Column(nullable = false)
    private String currency = "USD";
    @Column(nullable = false)
    private boolean active = true;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private Long version;

    protected Product() {
    }

    public Product(String sku, String name, String description, String category, BigDecimal price, Instant now) {
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.category = category;
        this.price = price;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, String description, String category, BigDecimal price, Boolean active, Instant now) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.price = price;
        if (active != null) {
            this.active = active;
        }
        this.updatedAt = now;
    }

    public void deactivate(Instant now) {
        this.active = false;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}

package com.enterprise.oms.order.entity;

import com.enterprise.oms.order.domain.CustomerTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class Customer {

    @Id
    private UUID id;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(nullable = false, length = 254)
    private String email;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CustomerTier tier;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Customer() {
    }

    public Customer(UUID id, String name, String email, CustomerTier tier, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.tier = tier;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public CustomerTier getTier() { return tier; }
    public Instant getCreatedAt() { return createdAt; }
}

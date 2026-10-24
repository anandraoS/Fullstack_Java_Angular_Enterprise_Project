package com.enterprise.oms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "inventory_reservation_items")
public class ReservationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "reservation_id", nullable = false, updatable = false)
    private UUID reservationId;
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;
    @Column(nullable = false, updatable = false)
    private int quantity;

    protected ReservationItem() {
    }

    public ReservationItem(UUID reservationId, UUID productId, int quantity) {
        this.reservationId = reservationId;
        this.productId = productId;
        this.quantity = quantity;
    }

    public UUID getId() { return id; }
    public UUID getReservationId() { return reservationId; }
    public UUID getProductId() { return productId; }
    public int getQuantity() { return quantity; }
}

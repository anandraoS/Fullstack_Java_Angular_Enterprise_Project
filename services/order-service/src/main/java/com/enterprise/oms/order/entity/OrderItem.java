package com.enterprise.oms.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;
    @Column(name = "line_no", nullable = false)
    private int lineNo;
    @Column(name = "product_id", nullable = false)
    private UUID productId;
    @Column(length = 64)
    private String sku;
    @Column(length = 200)
    private String name;
    @Column(nullable = false)
    private int quantity;
    @Column(name = "unit_price", precision = 19, scale = 2)
    private BigDecimal unitPrice;
    @Column(name = "line_total", precision = 19, scale = 2)
    private BigDecimal lineTotal;

    protected OrderItem() {
    }

    OrderItem(CustomerOrder order, int lineNo, UUID productId, int quantity) {
        this.id = UUID.randomUUID();
        this.order = order;
        this.lineNo = lineNo;
        this.productId = productId;
        this.quantity = quantity;
    }

    /** Price snapshot taken from the inventory validation response. */
    void applyPrice(String sku, String name, BigDecimal unitPrice) {
        this.sku = sku;
        this.name = name;
        this.unitPrice = unitPrice.setScale(2, RoundingMode.HALF_UP);
        this.lineTotal = this.unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    public UUID getId() { return id; }
    public int getLineNo() { return lineNo; }
    public UUID getProductId() { return productId; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public int getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getLineTotal() { return lineTotal; }
}

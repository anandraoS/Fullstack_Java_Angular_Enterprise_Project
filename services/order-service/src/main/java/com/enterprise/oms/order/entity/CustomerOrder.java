package com.enterprise.oms.order.entity;

import com.enterprise.oms.order.domain.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The order aggregate (entity name {@code CustomerOrder}: ORDER is a reserved word in SQL and HQL).
 * {@code @Version} gives optimistic locking: a cancel racing a saga event fails one of them cleanly instead of
 * silently overwriting; callers retry (see RetryingTransactions).
 */
@Entity(name = "CustomerOrder")
@Table(name = "orders")
public class CustomerOrder {

    @Id
    private UUID id;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
    @Column(name = "customer_email", nullable = false, length = 254)
    private String customerEmail;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;
    @Column(name = "status_reason", length = 1000)
    private String statusReason;
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;
    @Column(nullable = false, length = 3)
    private String currency;
    @Embedded
    private ShippingAddress shippingAddress;
    @Column(name = "correlation_id", length = 64)
    private String correlationId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private Long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    @BatchSize(size = 50)
    private List<OrderItem> items = new ArrayList<>();

    protected CustomerOrder() {
    }

    public CustomerOrder(UUID id, UUID customerId, String customerEmail, ShippingAddress shippingAddress,
                         String correlationId, Instant now) {
        this.id = id;
        this.customerId = customerId;
        this.customerEmail = customerEmail;
        this.shippingAddress = shippingAddress;
        this.correlationId = correlationId;
        this.status = OrderStatus.CREATED;
        this.totalAmount = BigDecimal.ZERO.setScale(2);
        this.currency = "USD";
        this.createdAt = now;
        this.updatedAt = now;
    }

    public OrderItem addItem(UUID productId, int quantity) {
        OrderItem item = new OrderItem(this, items.size() + 1, productId, quantity);
        items.add(item);
        return item;
    }

    /** Applies the price snapshot (one entry per line is required) and recomputes the total. */
    public void applyPrices(Map<UUID, PricedLine> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (OrderItem item : items) {
            PricedLine line = lines.get(item.getProductId());
            item.applyPrice(line.sku(), line.name(), line.unitPrice());
            total = total.add(item.getLineTotal());
        }
        this.totalAmount = total.setScale(2, RoundingMode.HALF_UP);
    }

    public record PricedLine(String sku, String name, BigDecimal unitPrice) {
    }

    /** State change only; history/audit are written by OrderTransitions. */
    public void changeStatus(OrderStatus to, String reason, Instant now) {
        this.status = to;
        this.statusReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 1000));
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public String getCustomerEmail() { return customerEmail; }
    public OrderStatus getStatus() { return status; }
    public String getStatusReason() { return statusReason; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
    public ShippingAddress getShippingAddress() { return shippingAddress; }
    public String getCorrelationId() { return correlationId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
    public List<OrderItem> getItems() { return items; }
}

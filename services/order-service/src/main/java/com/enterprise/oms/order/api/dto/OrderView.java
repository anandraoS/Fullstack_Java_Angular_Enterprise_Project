package com.enterprise.oms.order.api.dto;

import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.entity.CustomerOrder;
import com.enterprise.oms.order.entity.OrderItem;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Order JSON (contract 5.1). Prices/sku/name of the lines are null until validation has priced the order
 * (status CREATED); totalAmount is 0.00 until then. Money is scale 2, currency USD.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderView(UUID id, UUID customerId, OrderStatus status, String statusReason, BigDecimal totalAmount,
                        String currency, List<Item> items, AddressDto shippingAddress, String correlationId,
                        Instant createdAt, Instant updatedAt, Long version) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(UUID productId, String sku, String name, int quantity, BigDecimal unitPrice,
                       BigDecimal lineTotal) {
        static Item of(OrderItem i) {
            return new Item(i.getProductId(), i.getSku(), i.getName(), i.getQuantity(), i.getUnitPrice(),
                    i.getLineTotal());
        }
    }

    /** Must be called inside the transaction that loaded the order (items are lazy). */
    public static OrderView of(CustomerOrder o) {
        return new OrderView(o.getId(), o.getCustomerId(), o.getStatus(), o.getStatusReason(), o.getTotalAmount(),
                o.getCurrency(), o.getItems().stream().map(Item::of).toList(), AddressDto.of(o.getShippingAddress()),
                o.getCorrelationId(), o.getCreatedAt(), o.getUpdatedAt(), o.getVersion());
    }
}

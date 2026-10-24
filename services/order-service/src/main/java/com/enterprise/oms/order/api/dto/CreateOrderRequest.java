package com.enterprise.oms.order.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Schema(description = "Create-order request. Requires the Idempotency-Key header.")
public record CreateOrderRequest(
        @NotNull UUID customerId,
        @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid OrderItemRequest> items,
        @NotNull @Valid AddressDto shippingAddress) {

    /**
     * Canonical form used to fingerprint the request for Idempotency-Key reuse detection: independent of JSON
     * whitespace, property order and line order, sensitive to every business value.
     */
    public String canonical() {
        String lines = items.stream().sorted(Comparator.comparing(i -> i.productId().toString()))
                .map(i -> i.productId() + "x" + i.quantity()).collect(Collectors.joining(","));
        return customerId + "|" + lines + "|" + shippingAddress.line1().trim() + "|" + shippingAddress.city().trim()
                + "|" + shippingAddress.postalCode().trim() + "|" + shippingAddress.country().trim();
    }
}

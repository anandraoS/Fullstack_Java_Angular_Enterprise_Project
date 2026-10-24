package com.enterprise.oms.order.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "One order line: a product and a quantity (1-1000)")
public record OrderItemRequest(
        @NotNull UUID productId,
        @NotNull @Min(1) @Max(1000) Integer quantity) {
}

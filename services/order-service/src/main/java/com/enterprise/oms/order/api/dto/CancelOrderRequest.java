package com.enterprise.oms.order.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Cancellation request; the reason is optional but recommended")
public record CancelOrderRequest(@Size(max = 500) String reason) {
}

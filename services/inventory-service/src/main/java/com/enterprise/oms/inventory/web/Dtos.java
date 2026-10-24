package com.enterprise.oms.inventory.web;

import com.enterprise.oms.inventory.service.ProductView;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response records of the inventory REST API (contract section 5.2). */
public final class Dtos {

    private Dtos() {
    }

    /**
     * Product JSON. Stock fields ({@code onHand}, {@code reserved}, {@code version}) are present for OPERATOR/ADMIN
     * only; customers receive {@code available} alone (null fields are omitted).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductResponse(UUID id, String sku, String name, String description, String category,
                                  BigDecimal price, String currency, boolean active, Integer onHand,
                                  Integer reserved, int available, Long version) {

        public static ProductResponse of(ProductView v, boolean staff) {
            return new ProductResponse(v.id(), v.sku(), v.name(), v.description(), v.category(), v.price(),
                    v.currency(), v.active(), staff ? v.onHand() : null, staff ? v.reserved() : null,
                    Math.max(0, v.available()), staff ? v.version() : null);
        }
    }

    public record CreateProductRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]{2,30}$", message = "3-31 chars: letters, digits, hyphen")
            String sku,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotBlank @Size(max = 100) String category,
            @NotNull @DecimalMin(value = "0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
            @Pattern(regexp = "USD", message = "only USD is supported") String currency,
            @Min(0) @Max(10_000_000) Integer initialStock) {
    }

    public record UpdateProductRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotBlank @Size(max = 100) String category,
            @NotNull @DecimalMin(value = "0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
            Boolean active,
            Long version) {
    }

    public record StockAdjustRequest(
            @NotNull @Min(-1_000_000) @Max(1_000_000) Integer delta,
            @NotBlank @Size(min = 3, max = 500) String reason) {
    }

    public record ValidateRequest(@NotEmpty @Size(max = 50) List<@Valid @NotNull Line> items) {
        public record Line(@NotNull UUID productId, @Min(1) @Max(1000) int quantity) {
        }
    }

    /** Line reasons: NOT_FOUND, INACTIVE, INSUFFICIENT_STOCK (null when ok). */
    public record ValidateLine(UUID productId, String sku, String name, BigDecimal unitPrice, int available,
                               boolean active, boolean ok, String reason) {
    }

    public record ValidateResponse(boolean valid, List<ValidateLine> lines) {
    }

    public record ReservationItemResponse(UUID productId, String sku, int quantity) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReservationResponse(UUID id, UUID orderId, String status, List<ReservationItemResponse> items,
                                      Instant expiresAt, Instant createdAt, Instant updatedAt, String releaseReason,
                                      String failureReason, Instant shippedAt) {
    }

    public record MovementResponse(UUID id, UUID productId, String type, int onHandDelta, int reservedDelta,
                                   Integer onHandAfter, Integer reservedAfter, String reason, String reference,
                                   String actor, Instant occurredAt) {
    }
}

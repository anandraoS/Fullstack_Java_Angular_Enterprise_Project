package com.enterprise.oms.inventory.web;

import com.enterprise.oms.common.error.RequestValidationException;
import com.enterprise.oms.common.web.PageRequestFactory;
import com.enterprise.oms.common.web.PageResponse;
import com.enterprise.oms.inventory.domain.ReservationStatus;
import com.enterprise.oms.inventory.service.ProductView;
import com.enterprise.oms.inventory.service.ReservationQueryService;
import com.enterprise.oms.inventory.service.StockService;
import com.enterprise.oms.inventory.web.Dtos.MovementResponse;
import com.enterprise.oms.inventory.web.Dtos.ProductResponse;
import com.enterprise.oms.inventory.web.Dtos.ReservationResponse;
import com.enterprise.oms.inventory.web.Dtos.StockAdjustRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Inventory views, stock adjustment and reservation listing (OPERATOR / ADMIN). */
@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory")
@PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
public class InventoryController {

    private static final Set<String> INVENTORY_SORT = Set.of("sku", "name", "category", "onHand", "reserved", "available");
    private static final Set<String> RESERVATION_SORT = Set.of("createdAt", "updatedAt", "expiresAt", "status");
    private static final Set<String> MOVEMENT_SORT = Set.of("occurredAt");

    private final StockService stock;
    private final ReservationQueryService reservations;
    private final PageRequestFactory pages;

    public InventoryController(StockService stock, ReservationQueryService reservations, PageRequestFactory pages) {
        this.stock = stock;
        this.reservations = reservations;
        this.pages = pages;
    }

    @GetMapping
    @Operation(summary = "Stock overview (sku filter, lowStock) incl. on-hand / reserved / available")
    public PageResponse<ProductResponse> list(@RequestParam(required = false) String sku,
                                              @RequestParam(required = false, defaultValue = "false") boolean lowStock,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size,
                                              @RequestParam(required = false) List<String> sort) {
        if (sku != null && sku.length() > 64) {
            throw new RequestValidationException("sku", "must be at most 64 characters");
        }
        Pageable pageable = pages.create(page, size, sort, INVENTORY_SORT, Sort.by(Sort.Direction.ASC, "sku"));
        return PageResponse.from(stock.inventory(sku, lowStock, pageable), v -> ProductResponse.of(v, true));
    }

    @GetMapping("/reservations")
    @Operation(summary = "List reservations (orderId, status filters)")
    public PageResponse<ReservationResponse> reservations(@RequestParam(required = false) UUID orderId,
                                                          @RequestParam(required = false) String status,
                                                          @RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size,
                                                          @RequestParam(required = false) List<String> sort) {
        ReservationStatus parsed = null;
        if (status != null && !status.isBlank()) {
            try {
                parsed = ReservationStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new RequestValidationException("status", "must be one of RESERVED, COMMITTED, RELEASED, EXPIRED, FAILED");
            }
        }
        Pageable pageable = pages.create(page, size, sort, RESERVATION_SORT, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(reservations.list(orderId, parsed, pageable));
    }

    @GetMapping("/{productId}")
    public ProductResponse get(@PathVariable UUID productId) {
        ProductView v = stock.inventoryOf(productId);
        return ProductResponse.of(v, true);
    }

    @PutMapping("/{productId}/stock")
    @Operation(summary = "Adjust on-hand by delta (rejects a result below reserved with 409)")
    public ProductResponse adjust(@PathVariable UUID productId, @Valid @RequestBody StockAdjustRequest body) {
        return ProductResponse.of(stock.adjust(productId, body.delta(), body.reason()), true);
    }

    @GetMapping("/{productId}/movements")
    @Operation(summary = "Stock movement ledger of a product (newest first)")
    public PageResponse<MovementResponse> movements(@PathVariable UUID productId,
                                                    @RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size,
                                                    @RequestParam(required = false) List<String> sort) {
        Pageable pageable = pages.create(page, size, sort, MOVEMENT_SORT, Sort.by(Sort.Direction.DESC, "occurredAt"));
        return PageResponse.from(stock.movements(productId, pageable), m -> new MovementResponse(m.getId(),
                m.getProductId(), m.getType().name(), m.getOnHandDelta(), m.getReservedDelta(), m.getOnHandAfter(),
                m.getReservedAfter(), m.getReason(), m.getReference(), m.getActor(), m.getOccurredAt()));
    }
}

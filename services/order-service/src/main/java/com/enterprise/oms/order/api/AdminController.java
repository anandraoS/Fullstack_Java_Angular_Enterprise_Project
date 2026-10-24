package com.enterprise.oms.order.api;

import com.enterprise.oms.common.security.CurrentUser;
import com.enterprise.oms.common.web.PageRequestFactory;
import com.enterprise.oms.common.web.PageResponse;
import com.enterprise.oms.order.api.dto.OrderView;
import com.enterprise.oms.order.api.dto.SagaView;
import com.enterprise.oms.order.saga.OrderSagaOrchestrator;
import com.enterprise.oms.order.saga.ReconciliationResult;
import com.enterprise.oms.order.saga.ReconciliationService;
import com.enterprise.oms.order.service.OrderQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin", description = "Operational endpoints: failed orders, saga inspection, retry, reconciliation")
public class AdminController {

    private final OrderQueryService queries;
    private final OrderSagaOrchestrator saga;
    private final ReconciliationService reconciliation;
    private final PageRequestFactory pages;

    public AdminController(OrderQueryService queries, OrderSagaOrchestrator saga, ReconciliationService reconciliation,
                           PageRequestFactory pages) {
        this.queries = queries;
        this.saga = saga;
        this.reconciliation = reconciliation;
        this.pages = pages;
    }

    @GetMapping("/orders/failed")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    @Operation(summary = "Orders needing attention",
            description = "REJECTED, FAILED, and COMPENSATION_PENDING orders with no progress for oms.saga.stuck-after-seconds")
    public PageResponse<OrderView> failed(@RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        Pageable pageable = pages.create(page, size, (String) null, OrderController.SORTABLE, OrderController.DEFAULT_SORT);
        return queries.failed(pageable);
    }

    @PostMapping("/orders/{id}/retry")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    @Operation(summary = "Retry the current saga step of an order",
            description = "Re-sends the idempotent command of the current step (or re-runs validation) and resets the "
                    + "attempt counter. 409 INVALID_STATE_TRANSITION for orders in a terminal status.")
    public OrderView retry(@PathVariable UUID id) {
        saga.recover(id, true);
        return queries.get(id, CurrentUser.require());
    }

    @GetMapping("/saga/{orderId}")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    @Operation(summary = "Saga instance and step log of an order")
    public SagaView saga(@PathVariable UUID orderId) {
        return queries.saga(orderId);
    }

    @PostMapping("/reconciliation/run")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Run reconciliation now",
            description = "Detects order/inventory/fulfillment inconsistencies visible from this service and repairs "
                    + "them by re-issuing idempotent commands. Result is audited.")
    public ReconciliationResult reconcile() {
        return reconciliation.run();
    }
}

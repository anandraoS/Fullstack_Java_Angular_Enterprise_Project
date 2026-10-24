package com.enterprise.oms.order.api;

import com.enterprise.oms.common.error.RequestValidationException;
import com.enterprise.oms.common.security.CurrentUser;
import com.enterprise.oms.common.web.PageRequestFactory;
import com.enterprise.oms.common.web.PageResponse;
import com.enterprise.oms.order.api.dto.CancelOrderRequest;
import com.enterprise.oms.order.api.dto.CreateOrderRequest;
import com.enterprise.oms.order.api.dto.HistoryView;
import com.enterprise.oms.order.api.dto.OrderView;
import com.enterprise.oms.order.config.RateLimitProperties;
import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.ratelimit.OrderRateLimitedException;
import com.enterprise.oms.order.ratelimit.OrderRateLimiter;
import com.enterprise.oms.order.service.OrderCommandService;
import com.enterprise.oms.order.service.OrderQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Create, search, inspect and cancel orders")
public class OrderController {

    static final Set<String> SORTABLE = Set.of("createdAt", "updatedAt", "status", "totalAmount");
    static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");
    private static final String ROLES = "hasAnyRole('CUSTOMER','OPERATOR','ADMIN')";

    private final OrderCommandService commands;
    private final OrderQueryService queries;
    private final PageRequestFactory pages;
    private final OrderRateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProps;

    public OrderController(OrderCommandService commands, OrderQueryService queries, PageRequestFactory pages,
                           OrderRateLimiter rateLimiter, RateLimitProperties rateLimitProps) {
        this.commands = commands;
        this.queries = queries;
        this.pages = pages;
        this.rateLimiter = rateLimiter;
        this.rateLimitProps = rateLimitProps;
    }

    @PostMapping
    @PreAuthorize(ROLES)
    @Operation(summary = "Create an order (asynchronous, idempotent)",
            description = "Returns 202 with the order in status CREATED; the saga continues in the background. "
                    + "The Idempotency-Key header (8-64 chars A-Za-z0-9_-) is required: replaying the same key with "
                    + "the same body returns the original response with 'Idempotent-Replay: true'; the same key with "
                    + "a different body is 422 IDEMPOTENCY_KEY_REUSED. A CUSTOMER can only order for their own "
                    + "customerId (403 otherwise). Rate limited per caller (429 + Retry-After).")
    @ApiResponse(responseCode = "202", description = "Accepted; Location points to the order")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED or IDEMPOTENCY_KEY_REQUIRED", content = @Content)
    @ApiResponse(responseCode = "403", description = "customerId does not belong to the caller", content = @Content)
    @ApiResponse(responseCode = "404", description = "Unknown customer", content = @Content)
    @ApiResponse(responseCode = "422", description = "IDEMPOTENCY_KEY_REUSED", content = @Content)
    @ApiResponse(responseCode = "429", description = "RATE_LIMITED", content = @Content)
    public ResponseEntity<OrderView> create(
            @Parameter(description = "8-64 chars of A-Za-z0-9_-", required = true)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        CurrentUser user = CurrentUser.require();
        if (rateLimitProps.isEnabled()) {
            OrderRateLimiter.Decision d = rateLimiter.tryAcquire(user.id());
            if (!d.allowed()) {
                throw new OrderRateLimitedException(d.retryAfterSeconds());
            }
        }
        OrderCommandService.CreateResult result = commands.create(request, idempotencyKey, user);
        ResponseEntity.BodyBuilder response = ResponseEntity.accepted()
                .location(URI.create("/api/v1/orders/" + result.order().id()));
        if (result.replay()) {
            response.header("Idempotent-Replay", "true");
        }
        return response.body(result.order());
    }

    @GetMapping
    @PreAuthorize(ROLES)
    @Operation(summary = "Search orders",
            description = "CUSTOMER: always limited to their own orders (customerId is forced). Sort fields: "
                    + "createdAt, updatedAt, status, totalAmount. 'q': order id, or prefix of customer email / city.")
    public PageResponse<OrderView> search(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(name = "sort", required = false) List<String> sort) {
        if (q != null && q.length() > 100) {
            throw new RequestValidationException("q", "must be at most 100 characters");
        }
        Pageable pageable = pages.create(page, size, sort, SORTABLE, DEFAULT_SORT);
        return queries.search(new OrderQueryService.OrderSearch(status, customerId, from, to, q), pageable,
                CurrentUser.require());
    }

    @GetMapping("/{id}")
    @PreAuthorize(ROLES)
    @Operation(summary = "Get one order", description = "404 when the order does not exist or belongs to another customer")
    public OrderView get(@PathVariable UUID id) {
        return queries.get(id, CurrentUser.require());
    }

    @GetMapping("/{id}/history")
    @PreAuthorize(ROLES)
    @Operation(summary = "Status history of an order, oldest first")
    public List<HistoryView> history(@PathVariable UUID id) {
        return queries.history(id, CurrentUser.require());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(ROLES)
    @Operation(summary = "Cancel an order",
            description = "Allowed from CREATED, VALIDATED, INVENTORY_RESERVED, CONFIRMED; otherwise 409 "
                    + "INVALID_STATE_TRANSITION. After a reservation exists the order goes through "
                    + "COMPENSATION_PENDING until the inventory is released.")
    public OrderView cancel(@PathVariable UUID id,
                            @Valid @RequestBody(required = false) @Schema(nullable = true) CancelOrderRequest request) {
        return commands.cancel(id, request == null ? null : request.reason(), CurrentUser.require());
    }
}

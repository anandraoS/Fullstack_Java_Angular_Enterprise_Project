package com.enterprise.oms.fulfillment.api;

import com.enterprise.oms.common.error.RequestValidationException;
import com.enterprise.oms.common.web.PageRequestFactory;
import com.enterprise.oms.common.web.PageResponse;
import com.enterprise.oms.fulfillment.domain.FulfillmentStatus;
import com.enterprise.oms.fulfillment.service.FulfillmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Contract 5.3. CUSTOMER tokens are not allowed (403) on any of these endpoints. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Fulfillments")
@PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
public class FulfillmentController {

    private static final Set<String> SORTABLE = Set.of("createdAt", "updatedAt", "status", "attempts");

    private final FulfillmentService service;
    private final PageRequestFactory pages;

    public FulfillmentController(FulfillmentService service, PageRequestFactory pages) {
        this.service = service;
        this.pages = pages;
    }

    @GetMapping("/fulfillments")
    @Operation(summary = "List fulfillments (OPERATOR, ADMIN)")
    public PageResponse<FulfillmentView> list(@RequestParam(required = false) UUID orderId,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size,
                                              @RequestParam(required = false) List<String> sort) {
        FulfillmentStatus parsed = null;
        if (status != null && !status.isBlank()) {
            try {
                parsed = FulfillmentStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new RequestValidationException("status", "must be one of PENDING, PICKING, PACKED, SHIPPED, COMPLETED, FAILED");
            }
        }
        return service.search(orderId, parsed,
                pages.create(page, size, sort, SORTABLE, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @GetMapping("/fulfillments/{id}")
    @Operation(summary = "Get one fulfillment (OPERATOR, ADMIN)")
    public FulfillmentView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/admin/fulfillments/{id}/retry")
    @Operation(summary = "Retry a FAILED fulfillment: FAILED -> PENDING, attempts reset (409 if not FAILED)")
    public FulfillmentView retry(@PathVariable UUID id) {
        return service.retry(id);
    }
}

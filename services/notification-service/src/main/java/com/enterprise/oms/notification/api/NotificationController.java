package com.enterprise.oms.notification.api;

import com.enterprise.oms.common.error.ForbiddenException;
import com.enterprise.oms.common.error.RequestValidationException;
import com.enterprise.oms.common.security.CurrentUser;
import com.enterprise.oms.common.web.PageRequestFactory;
import com.enterprise.oms.common.web.PageResponse;
import com.enterprise.oms.notification.domain.NotificationStatus;
import com.enterprise.oms.notification.service.NotificationService;
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

/** Contract 5.4. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Notifications")
public class NotificationController {

    private static final Set<String> SORTABLE = Set.of("createdAt", "sentAt", "status", "attempts", "template");

    private final NotificationService service;
    private final PageRequestFactory pages;

    public NotificationController(NotificationService service, PageRequestFactory pages) {
        this.service = service;
        this.pages = pages;
    }

    /**
     * CUSTOMER: the customerId filter is FORCED to the token's customerId (a different {@code customerId} parameter is
     * ignored, never honoured), so a customer can only ever see their own notifications.
     */
    @GetMapping("/notifications")
    @PreAuthorize("hasAnyRole('CUSTOMER','OPERATOR','ADMIN')")
    @Operation(summary = "List notifications (CUSTOMER: own only, forced filter; OPERATOR/ADMIN: all)")
    public PageResponse<NotificationView> list(@RequestParam(required = false) UUID customerId,
                                               @RequestParam(required = false) UUID orderId,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size,
                                               @RequestParam(required = false) List<String> sort) {
        CurrentUser user = CurrentUser.require();
        UUID effectiveCustomer = customerId;
        if (!user.isStaff()) {
            if (user.customerId() == null) {
                throw new ForbiddenException("Token has no customerId");
            }
            effectiveCustomer = user.customerId();
        }
        NotificationStatus parsed = null;
        if (status != null && !status.isBlank()) {
            try {
                parsed = NotificationStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new RequestValidationException("status", "must be one of PENDING, SENT, FAILED, RETRYING");
            }
        }
        return service.search(effectiveCustomer, orderId, parsed,
                pages.create(page, size, sort, SORTABLE, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @PostMapping("/admin/notifications/{id}/retry")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    @Operation(summary = "Retry a FAILED notification: FAILED -> PENDING with a fresh attempt budget (409 otherwise)")
    public NotificationView retry(@PathVariable UUID id) {
        return service.retry(id);
    }
}

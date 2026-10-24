package com.enterprise.oms.common.audit;

import com.enterprise.oms.common.web.PageRequestFactory;
import com.enterprise.oms.common.web.PageResponse;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** {@code GET /api/v1/ops/audit?entityType=&entityId=&page&size&sort=} (OPERATOR, ADMIN). */
@RestController
@RequestMapping("/api/v1/ops")
public class OpsAuditController {

    private static final Set<String> SORTABLE = Set.of("occurredAt", "entityType", "action", "actor");

    private final AuditService audit;
    private final PageRequestFactory pages;

    public OpsAuditController(AuditService audit, PageRequestFactory pages) {
        this.audit = audit;
        this.pages = pages;
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public PageResponse<AuditEventView> audit(@RequestParam(required = false) String entityType,
                                              @RequestParam(required = false) String entityId,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size,
                                              @RequestParam(required = false) List<String> sort) {
        return audit.search(entityType, entityId,
                pages.create(page, size, sort, SORTABLE, Sort.by(Sort.Direction.DESC, "occurredAt")));
    }
}

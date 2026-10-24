package com.enterprise.oms.common.messaging;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/ops/messaging} for every service (OPERATOR, ADMIN). Disable via oms.messaging.ops-endpoint-enabled=false. */
@RestController
@RequestMapping("/api/v1/ops")
public class OpsMessagingController {

    private final MessagingStatusService service;

    public OpsMessagingController(MessagingStatusService service) {
        this.service = service;
    }

    @GetMapping("/messaging")
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public MessagingStatus messaging() {
        return service.status();
    }
}

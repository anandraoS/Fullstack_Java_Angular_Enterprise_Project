package com.enterprise.oms.inventory.web;

import com.enterprise.oms.inventory.service.ValidationService;
import com.enterprise.oms.inventory.web.Dtos.ValidateRequest;
import com.enterprise.oms.inventory.web.Dtos.ValidateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service endpoints: SERVICE role only (enforced by the platform chain AND here), never routed by the gateway. */
@RestController
@RequestMapping("/api/v1/internal")
@Tag(name = "Internal")
@PreAuthorize("hasRole('SERVICE')")
public class InternalController {

    private final ValidationService validation;

    public InternalController(ValidationService validation) {
        this.validation = validation;
    }

    @PostMapping("/products/validate")
    @Operation(summary = "Validate products/quantities for an order (advisory; reservation is authoritative)")
    public ValidateResponse validate(@Valid @RequestBody ValidateRequest request) {
        return validation.validate(request);
    }
}

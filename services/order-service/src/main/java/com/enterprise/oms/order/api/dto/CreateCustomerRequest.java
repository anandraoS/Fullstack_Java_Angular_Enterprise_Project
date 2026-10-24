package com.enterprise.oms.order.api.dto;

import com.enterprise.oms.order.domain.CustomerTier;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Create-customer request")
public record CreateCustomerRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Email @Size(max = 254) String email,
        CustomerTier tier) {
}

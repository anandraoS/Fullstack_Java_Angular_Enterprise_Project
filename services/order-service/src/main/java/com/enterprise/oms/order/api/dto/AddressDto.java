package com.enterprise.oms.order.api.dto;

import com.enterprise.oms.order.entity.ShippingAddress;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Shipping address")
public record AddressDto(
        @NotBlank @Size(max = 200) String line1,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 20) String postalCode,
        @NotBlank @Size(min = 2, max = 64) String country) {

    public static AddressDto of(ShippingAddress a) {
        return new AddressDto(a.getLine1(), a.getCity(), a.getPostalCode(), a.getCountry());
    }

    public ShippingAddress toEntity() {
        return new ShippingAddress(line1.trim(), city.trim(), postalCode.trim(), country.trim());
    }
}

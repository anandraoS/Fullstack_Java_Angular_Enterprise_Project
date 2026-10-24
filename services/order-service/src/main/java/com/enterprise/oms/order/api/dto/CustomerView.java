package com.enterprise.oms.order.api.dto;

import com.enterprise.oms.order.domain.CustomerTier;
import com.enterprise.oms.order.entity.Customer;

import java.time.Instant;
import java.util.UUID;

public record CustomerView(UUID id, String name, String email, CustomerTier tier, Instant createdAt) {

    public static CustomerView of(Customer c) {
        return new CustomerView(c.getId(), c.getName(), c.getEmail(), c.getTier(), c.getCreatedAt());
    }
}

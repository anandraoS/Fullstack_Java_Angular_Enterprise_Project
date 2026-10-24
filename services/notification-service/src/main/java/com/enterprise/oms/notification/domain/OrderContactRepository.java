package com.enterprise.oms.notification.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrderContactRepository extends JpaRepository<OrderContact, UUID> {
}

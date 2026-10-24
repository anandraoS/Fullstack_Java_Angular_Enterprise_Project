package com.enterprise.oms.order.repository;

import com.enterprise.oms.order.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer> {

    boolean existsByEmailIgnoreCase(String email);
}

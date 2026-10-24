package com.enterprise.oms.order.service;

import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.common.error.ConflictException;
import com.enterprise.oms.common.error.NotFoundException;
import com.enterprise.oms.common.security.CurrentUser;
import com.enterprise.oms.common.web.PageResponse;
import com.enterprise.oms.order.api.dto.CreateCustomerRequest;
import com.enterprise.oms.order.api.dto.CustomerView;
import com.enterprise.oms.order.domain.CustomerTier;
import com.enterprise.oms.order.entity.Customer;
import com.enterprise.oms.order.repository.CustomerRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final AuditService audit;

    public CustomerService(CustomerRepository customers, AuditService audit) {
        this.customers = customers;
        this.audit = audit;
    }

    @Transactional
    public CustomerView create(CreateCustomerRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (customers.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("A customer with this email already exists");
        }
        Customer saved = customers.saveAndFlush(new Customer(UUID.randomUUID(), request.name().trim(), email,
                request.tier() == null ? CustomerTier.STANDARD : request.tier(), Instant.now()));
        audit.record("Customer", saved.getId(), "CUSTOMER_CREATED", Map.of("tier", saved.getTier().name()));
        return CustomerView.of(saved);
    }

    /** A CUSTOMER can read only themselves; for anyone else the answer is 404 (no enumeration). */
    @Transactional(readOnly = true)
    public CustomerView get(UUID id, CurrentUser user) {
        if (!user.canAccessCustomer(id)) {
            throw new NotFoundException("Customer", id);
        }
        return customers.findById(id).map(CustomerView::of).orElseThrow(() -> new NotFoundException("Customer", id));
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerView> search(String q, Pageable pageable) {
        Specification<Customer> spec = Specification.where(null);
        if (q != null && !q.isBlank()) {
            String like = OrderSpecifications.escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
            spec = spec.and((root, query, cb) -> cb.or(cb.like(cb.lower(root.get("name")), like, '\\'),
                    cb.like(cb.lower(root.get("email")), like, '\\')));
        }
        return PageResponse.from(customers.findAll(spec, pageable), CustomerView::of);
    }
}

package com.enterprise.oms.order.api;

import com.enterprise.oms.common.security.CurrentUser;
import com.enterprise.oms.common.web.PageRequestFactory;
import com.enterprise.oms.common.web.PageResponse;
import com.enterprise.oms.order.api.dto.CreateCustomerRequest;
import com.enterprise.oms.order.api.dto.CustomerView;
import com.enterprise.oms.order.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customers")
public class CustomerController {

    private static final Set<String> SORTABLE = Set.of("name", "email", "createdAt");

    private final CustomerService customers;
    private final PageRequestFactory pages;

    public CustomerController(CustomerService customers, PageRequestFactory pages) {
        this.customers = customers;
        this.pages = pages;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Create a customer", description = "ADMIN or OPERATOR; 409 if the email already exists")
    public ResponseEntity<CustomerView> create(@Valid @RequestBody CreateCustomerRequest request) {
        CustomerView created = customers.create(request);
        return ResponseEntity.created(URI.create("/api/v1/customers/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER','OPERATOR','ADMIN')")
    @Operation(summary = "Get a customer", description = "A CUSTOMER can read only themselves (others: 404)")
    public CustomerView get(@PathVariable UUID id) {
        return customers.get(id, CurrentUser.require());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    @Operation(summary = "Search customers", description = "q: prefix of name or email")
    public PageResponse<CustomerView> search(@RequestParam(required = false) String q,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size,
                                             @RequestParam(name = "sort", required = false) List<String> sort) {
        Pageable pageable = pages.create(page, size, sort, SORTABLE, Sort.by("name"));
        return customers.search(q, pageable);
    }
}

package com.enterprise.oms.inventory.web;

import com.enterprise.oms.common.error.RequestValidationException;
import com.enterprise.oms.common.security.CurrentUser;
import com.enterprise.oms.common.web.PageRequestFactory;
import com.enterprise.oms.common.web.PageResponse;
import com.enterprise.oms.inventory.service.ProductService;
import com.enterprise.oms.inventory.service.ProductView;
import com.enterprise.oms.inventory.web.Dtos.CreateProductRequest;
import com.enterprise.oms.inventory.web.Dtos.ProductResponse;
import com.enterprise.oms.inventory.web.Dtos.UpdateProductRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Product catalog (contract 5.2). Customers see {@code available} only; staff also see stock fields. */
@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products")
public class ProductController {

    static final Set<String> SORTABLE = Set.of("sku", "name", "category", "price", "createdAt", "available");
    private static final int MAX_Q = 100;

    private final ProductService products;
    private final PageRequestFactory pages;

    public ProductController(ProductService products, PageRequestFactory pages) {
        this.products = products;
        this.pages = pages;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CUSTOMER','OPERATOR','ADMIN')")
    @Operation(summary = "Search products (q, category, inStock) with pagination")
    public PageResponse<ProductResponse> list(@RequestParam(required = false) String q,
                                              @RequestParam(required = false) String category,
                                              @RequestParam(required = false) Boolean inStock,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size,
                                              @RequestParam(required = false) List<String> sort) {
        if (q != null && q.length() > MAX_Q) {
            throw new RequestValidationException("q", "must be at most " + MAX_Q + " characters");
        }
        Pageable pageable = pages.create(page, size, sort, SORTABLE, Sort.by(Sort.Direction.ASC, "sku"));
        CurrentUser user = CurrentUser.require();
        boolean staff = user.isStaff();
        return PageResponse.from(products.search(q, category, inStock, pageable, user),
                v -> ProductResponse.of(v, staff));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER','OPERATOR','ADMIN')")
    public ProductResponse get(@PathVariable UUID id) {
        CurrentUser user = CurrentUser.require();
        return ProductResponse.of(products.get(id, user), user.isStaff());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a product (ADMIN)")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest body) {
        ProductView v = products.create(body.sku(), body.name(), body.description(), body.category(), body.price(),
                body.initialStock() == null ? 0 : body.initialStock());
        return ResponseEntity.created(URI.create("/api/v1/products/" + v.id())).body(ProductResponse.of(v, true));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update mutable product fields (ADMIN); the SKU is immutable")
    public ProductResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateProductRequest body) {
        return ProductResponse.of(products.update(id, body.name(), body.description(), body.category(), body.price(),
                body.active(), body.version()), true);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Deactivate a product (ADMIN); idempotent, returns the product")
    public ProductResponse deactivate(@PathVariable UUID id) {
        return ProductResponse.of(products.deactivate(id), true);
    }
}

package com.enterprise.oms.inventory.service;

import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.common.error.ConflictException;
import com.enterprise.oms.common.error.NotFoundException;
import com.enterprise.oms.common.security.CurrentUser;
import com.enterprise.oms.inventory.cache.ProductCache;
import com.enterprise.oms.inventory.cache.ProductCacheInvalidator;
import com.enterprise.oms.inventory.domain.MovementType;
import com.enterprise.oms.inventory.domain.Product;
import com.enterprise.oms.inventory.domain.Stock;
import com.enterprise.oms.inventory.domain.StockMovement;
import com.enterprise.oms.inventory.repo.ProductQueryRepository;
import com.enterprise.oms.inventory.repo.ProductRepository;
import com.enterprise.oms.inventory.repo.StockMovementRepository;
import com.enterprise.oms.inventory.repo.StockRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Product catalog: search/read for everybody (shaped per role by the controller), CRUD for ADMIN. */
@Service
public class ProductService {

    private final ProductRepository products;
    private final StockRepository stock;
    private final StockMovementRepository movements;
    private final ProductQueryRepository query;
    private final ProductCache cache;
    private final ProductCacheInvalidator invalidator;
    private final AuditService audit;
    private final Clock clock;

    public ProductService(ProductRepository products, StockRepository stock, StockMovementRepository movements,
                          ProductQueryRepository query, ProductCache cache, ProductCacheInvalidator invalidator,
                          AuditService audit, Clock clock) {
        this.products = products;
        this.stock = stock;
        this.movements = movements;
        this.query = query;
        this.cache = cache;
        this.invalidator = invalidator;
        this.audit = audit;
        this.clock = clock;
    }

    /** Customers only ever see active products; staff see everything. Always reads the database (never the cache). */
    @Transactional(readOnly = true)
    public Page<ProductView> search(String q, String category, Boolean inStock, Pageable pageable, CurrentUser user) {
        return query.search(new ProductQueryRepository.Filter(q, null, category, inStock, null, !user.isStaff()),
                pageable);
    }

    /** Read-through cached lookup (see {@link ProductCache} for the staleness contract). */
    @Transactional(readOnly = true)
    public ProductView get(UUID id, CurrentUser user) {
        Optional<ProductView> cached = cache.get(id);
        ProductView view = cached.orElseGet(() -> {
            ProductView loaded = load(id);
            cache.put(loaded);
            return loaded;
        });
        if (!view.active() && !user.isStaff()) {
            throw new NotFoundException("Product", id);   // do not reveal deactivated products to customers
        }
        return view;
    }

    /** Fresh database read (used after writes and by the inventory views). */
    @Transactional(readOnly = true)
    public ProductView load(UUID id) {
        Product p = products.findById(id).orElseThrow(() -> new NotFoundException("Product", id));
        return ProductView.of(p, stock.findById(id).orElse(null));
    }

    @Transactional
    public ProductView create(String sku, String name, String description, String category, BigDecimal price,
                              int initialStock) {
        String normalizedSku = sku.trim().toUpperCase(java.util.Locale.ROOT);
        if (products.existsBySku(normalizedSku)) {
            throw new ConflictException("A product with SKU " + normalizedSku + " already exists");
        }
        Instant now = clock.instant();
        Product p;
        try {
            p = products.saveAndFlush(new Product(normalizedSku, name.trim(), description, category.trim(),
                    price, now));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("A product with SKU " + normalizedSku + " already exists");
        }
        Stock s = stock.save(new Stock(p.getId(), initialStock, now));
        if (initialStock > 0) {
            movements.save(new StockMovement(p.getId(), MovementType.ADJUSTMENT, initialStock, 0, initialStock, 0,
                    "initial stock", null, Actors.current(), now));
        }
        audit.record("Product", p.getId(), "PRODUCT_CREATED",
                Map.of("sku", normalizedSku, "price", price, "initialStock", initialStock));
        return ProductView.of(p, s);
    }

    /**
     * @param expectedVersion optional optimistic precondition (the product {@code version} the client last saw);
     *                        mismatch = 409 CONFLICT
     */
    @Transactional
    public ProductView update(UUID id, String name, String description, String category, BigDecimal price,
                              Boolean active, Long expectedVersion) {
        Product p = products.findById(id).orElseThrow(() -> new NotFoundException("Product", id));
        if (expectedVersion != null && !expectedVersion.equals(p.getVersion())) {
            throw new ConflictException("Product was modified by someone else (current version " + p.getVersion() + ")");
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("fromPrice", p.getPrice());
        p.update(name.trim(), description, category.trim(), price, active, clock.instant());
        changes.put("toPrice", price);
        changes.put("active", p.isActive());
        products.saveAndFlush(p);
        audit.record("Product", id, "PRODUCT_UPDATED", changes);
        invalidator.evictAfterCommit(id);
        return ProductView.of(p, stock.findById(id).orElse(null));
    }

    /** DELETE = deactivate (idempotent). Existing reservations are unaffected; new ones are refused. */
    @Transactional
    public ProductView deactivate(UUID id) {
        Product p = products.findById(id).orElseThrow(() -> new NotFoundException("Product", id));
        if (p.isActive()) {
            p.deactivate(clock.instant());
            products.saveAndFlush(p);
            audit.record("Product", id, "PRODUCT_DEACTIVATED", Map.of("sku", p.getSku()));
        }
        invalidator.evictAfterCommit(id);
        return ProductView.of(p, stock.findById(id).orElse(null));
    }
}

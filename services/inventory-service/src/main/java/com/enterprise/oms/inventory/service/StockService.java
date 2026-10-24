package com.enterprise.oms.inventory.service;

import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.common.error.ConflictException;
import com.enterprise.oms.common.error.NotFoundException;
import com.enterprise.oms.common.error.RequestValidationException;
import com.enterprise.oms.inventory.cache.ProductCacheInvalidator;
import com.enterprise.oms.inventory.config.InventoryProperties;
import com.enterprise.oms.inventory.domain.MovementType;
import com.enterprise.oms.inventory.domain.Product;
import com.enterprise.oms.inventory.domain.Stock;
import com.enterprise.oms.inventory.domain.StockMovement;
import com.enterprise.oms.inventory.metrics.InventoryMetrics;
import com.enterprise.oms.inventory.repo.ProductQueryRepository;
import com.enterprise.oms.inventory.repo.ProductRepository;
import com.enterprise.oms.inventory.repo.StockMovementRepository;
import com.enterprise.oms.inventory.repo.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

/** Stock views and manual adjustments (optimistic {@code @Version} + bounded retry). */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final StockRepository stock;
    private final ProductRepository products;
    private final StockMovementRepository movements;
    private final ProductQueryRepository query;
    private final ProductCacheInvalidator invalidator;
    private final AuditService audit;
    private final InventoryProperties props;
    private final InventoryMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate tx;

    public StockService(StockRepository stock, ProductRepository products, StockMovementRepository movements,
                        ProductQueryRepository query, ProductCacheInvalidator invalidator, AuditService audit,
                        InventoryProperties props, InventoryMetrics metrics, Clock clock,
                        PlatformTransactionManager txManager) {
        this.stock = stock;
        this.products = products;
        this.movements = movements;
        this.query = query;
        this.invalidator = invalidator;
        this.audit = audit;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Transactional(readOnly = true)
    public Page<ProductView> inventory(String sku, boolean lowStock, Pageable pageable) {
        Integer lowAt = lowStock ? props.getLowStockThreshold() : null;
        return query.search(new ProductQueryRepository.Filter(null, sku, null, null, lowAt, false), pageable);
    }

    @Transactional(readOnly = true)
    public ProductView inventoryOf(UUID productId) {
        Product p = products.findById(productId).orElseThrow(() -> new NotFoundException("Product", productId));
        Stock s = stock.findById(productId).orElseThrow(() -> new NotFoundException("Stock of product", productId));
        return ProductView.of(p, s);
    }

    @Transactional(readOnly = true)
    public Page<StockMovement> movements(UUID productId, Pageable pageable) {
        if (!products.existsById(productId)) {
            throw new NotFoundException("Product", productId);
        }
        return movements.findByProductId(productId, pageable);
    }

    /**
     * on_hand += delta. Rejected with 409 CONFLICT when the result would fall below {@code reserved} (that stock is
     * promised to orders). The row carries a {@code @Version}; every concurrent writer (reservations included) bumps
     * it, so a stale read fails the version check and the whole read-check-write is retried up to
     * {@code oms.inventory.stock-adjust-max-retries} times before giving up with 409.
     */
    public ProductView adjust(UUID productId, int delta, String reason) {
        if (delta == 0) {
            throw new RequestValidationException("delta", "must not be zero");
        }
        int max = Math.max(1, props.getStockAdjustMaxRetries());
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                return tx.execute(status -> doAdjust(productId, delta, reason));
            } catch (OptimisticLockingFailureException | PessimisticLockingFailureException e) {
                metrics.conflict();
                log.debug("Stock adjust of {} lost an optimistic race (attempt {}/{})", productId, attempt, max);
                LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(1_000_000L, 5_000_000L * attempt));
            }
        }
        throw new ConflictException("Stock of product " + productId + " is being modified concurrently, please retry");
    }

    private ProductView doAdjust(UUID productId, int delta, String reason) {
        Product p = products.findById(productId).orElseThrow(() -> new NotFoundException("Product", productId));
        Stock s = stock.findById(productId).orElseThrow(() -> new NotFoundException("Stock of product", productId));
        int before = s.getOnHand();
        long result = (long) before + delta;
        if (result < s.getReserved()) {
            throw new ConflictException("Resulting on-hand " + result + " would be below the " + s.getReserved()
                    + " units already reserved for orders");
        }
        if (result > 100_000_000L) {
            throw new RequestValidationException("delta", "resulting on-hand is unreasonably large");
        }
        Instant now = clock.instant();
        s.setOnHand((int) result, now);
        stock.saveAndFlush(s);    // version check happens HERE (UPDATE ... WHERE version = ?), inside the retry loop
        movements.save(new StockMovement(productId, MovementType.ADJUSTMENT, delta, 0, (int) result, s.getReserved(),
                reason, null, Actors.current(), now));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("sku", p.getSku());
        detail.put("delta", delta);
        detail.put("onHandBefore", before);
        detail.put("onHandAfter", (int) result);
        detail.put("reserved", s.getReserved());
        detail.put("reason", reason);
        audit.record("Stock", productId, "STOCK_ADJUSTED", detail);
        invalidator.evictAfterCommit(productId);
        return ProductView.of(p, s);
    }
}

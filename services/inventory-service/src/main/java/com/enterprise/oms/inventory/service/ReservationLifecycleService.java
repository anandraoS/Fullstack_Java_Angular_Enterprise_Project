package com.enterprise.oms.inventory.service;

import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.common.web.CorrelationIds;
import com.enterprise.oms.inventory.cache.ProductCacheInvalidator;
import com.enterprise.oms.inventory.config.InventoryProperties;
import com.enterprise.oms.inventory.domain.MovementType;
import com.enterprise.oms.inventory.domain.Reservation;
import com.enterprise.oms.inventory.domain.ReservationItem;
import com.enterprise.oms.inventory.domain.ReservationStatus;
import com.enterprise.oms.inventory.domain.StockMovement;
import com.enterprise.oms.inventory.metrics.InventoryMetrics;
import com.enterprise.oms.inventory.repo.ReservationClaims;
import com.enterprise.oms.inventory.repo.ReservationItemRepository;
import com.enterprise.oms.inventory.repo.ReservationRepository;
import com.enterprise.oms.inventory.repo.StockMovementRepository;
import com.enterprise.oms.inventory.repo.StockRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything that happens to a reservation after it was created: commit (OrderConfirmed), release (ReleaseInventory /
 * OrderCancelled), ship (FulfillmentCompleted) and TTL expiry. Every state change is a status-guarded UPDATE, so two
 * concurrent actors (expiry job vs OrderConfirmed, duplicate commands, several service instances) can never both win,
 * and replays are harmless no-ops. Stock is always given back in ascending productId order.
 */
@Service
public class ReservationLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ReservationLifecycleService.class);

    /** What a lifecycle call did. */
    public enum Result {
        /** State changed now. */
        APPLIED,
        /** Already in the target state (replay / duplicate): nothing done. */
        NO_OP,
        /** No reservation exists for the order. */
        NOT_FOUND,
        /** The reservation is in a state that forbids the action (e.g. commit after expiry, release after shipping). */
        REJECTED
    }

    private final ReservationRepository reservations;
    private final ReservationClaims claims;
    private final ReservationItemRepository reservationItems;
    private final StockRepository stock;
    private final StockMovementRepository movements;
    private final InventoryEventPublisher events;
    private final AuditService audit;
    private final InventoryMetrics metrics;
    private final ProductCacheInvalidator cache;
    private final InventoryProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;

    @PersistenceContext
    private EntityManager em;

    public ReservationLifecycleService(ReservationRepository reservations, ReservationClaims claims,
                                       ReservationItemRepository reservationItems, StockRepository stock,
                                       StockMovementRepository movements, InventoryEventPublisher events,
                                       AuditService audit, InventoryMetrics metrics, ProductCacheInvalidator cache,
                                       InventoryProperties props, Clock clock, PlatformTransactionManager txManager) {
        this.reservations = reservations;
        this.claims = claims;
        this.reservationItems = reservationItems;
        this.stock = stock;
        this.movements = movements;
        this.events = events;
        this.audit = audit;
        this.metrics = metrics;
        this.cache = cache;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /** OrderConfirmed: RESERVED -> COMMITTED, TTL cleared. */
    @Transactional
    public Result commit(UUID orderId) {
        Reservation r = reservations.findByOrderId(orderId).orElse(null);
        if (r == null) {
            log.warn("OrderConfirmed for order {} without a reservation (ignored)", orderId);
            return Result.NOT_FOUND;
        }
        if (r.getStatus() == ReservationStatus.COMMITTED) {
            return Result.NO_OP;
        }
        if (r.getStatus() == ReservationStatus.RESERVED && reservations.markCommitted(r.getId(), clock.instant()) == 1) {
            audit.record("Reservation", r.getId(), "COMMITTED", Map.of("orderId", orderId));
            TxHooks.afterCommit(() -> metrics.reservation("committed"));
            return Result.APPLIED;
        }
        // lost against the expiry job / a release (or it changed under us): the confirmation is too late
        Reservation now = fresh(orderId);
        if (now == null) {
            now = r;
        }
        if (now.getStatus() == ReservationStatus.COMMITTED) {
            return Result.NO_OP;
        }
        log.warn("OrderConfirmed arrived for order {} but its reservation is {}: stock is NOT held (reconciliation needed)",
                orderId, now.getStatus());
        TxHooks.afterCommit(metrics::lateCommit);
        return Result.REJECTED;
    }

    /**
     * ReleaseInventory / OrderCancelled: RESERVED|COMMITTED -> RELEASED, stock given back, InventoryReleased emitted.
     *
     * @param answerCommand true for the ReleaseInventory COMMAND: the saga is waiting for InventoryReleased, so a
     *                      repeat (or a release with nothing to release) is answered again. For the OrderCancelled
     *                      EVENT nobody waits, so replays and tombstones stay silent.
     */
    @Transactional
    public Result release(UUID orderId, String reason, boolean answerCommand, String correlationId, UUID causationId) {
        Instant now = clock.instant();
        for (int attempt = 0; attempt < 3; attempt++) {
            Reservation r = attempt == 0 ? reservations.findByOrderId(orderId).orElse(null) : fresh(orderId);
            if (r == null) {
                UUID tombstoneId = UUID.randomUUID();
                if (!claims.claimReleasedTombstone(tombstoneId, orderId, reason, now)) {
                    continue;   // a reservation appeared concurrently: re-read it
                }
                // blocks a later/duplicate ReserveInventory of this (cancelled) order from ever taking stock
                audit.record("Reservation", tombstoneId, "RELEASED_NOTHING_RESERVED",
                        Map.of("orderId", orderId, "reason", reason));
                if (answerCommand) {
                    events.released(orderId, tombstoneId, reason, correlationId, causationId);
                }
                return Result.NOT_FOUND;
            }
            switch (r.getStatus()) {
                case RESERVED, COMMITTED -> {
                    if (r.getShippedAt() != null) {
                        log.warn("Release for order {} ignored: goods already shipped", orderId);
                        return Result.REJECTED;
                    }
                    if (reservations.markReleased(r.getId(), r.getStatus(), reason, now) == 1) {
                        giveBackStock(r.getId(), orderId, MovementType.RELEASE, reason, now);
                        events.released(orderId, r.getId(), reason, correlationId, causationId);
                        audit.record("Reservation", r.getId(), "RELEASED", Map.of("orderId", orderId, "reason", reason));
                        TxHooks.afterCommit(() -> metrics.reservation("released"));
                        return Result.APPLIED;
                    }
                    // status changed between read and update: loop and look again
                }
                case RELEASED, EXPIRED -> {
                    if (answerCommand) {
                        events.released(orderId, r.getId(), r.getReleaseReason(), correlationId, causationId);
                    }
                    return Result.NO_OP;
                }
                case FAILED -> {
                    if (answerCommand) {
                        events.released(orderId, r.getId(), reason, correlationId, causationId);
                    }
                    return Result.NO_OP;
                }
                default -> throw new IllegalStateException("Unexpected status " + r.getStatus());
            }
        }
        throw new IllegalStateException("Reservation of order " + orderId + " kept changing during release; retry");
    }

    /** FulfillmentCompleted: the goods left the warehouse: on_hand -= q and reserved -= q, exactly once. */
    @Transactional
    public Result ship(UUID orderId) {
        Reservation r = reservations.findByOrderId(orderId).orElse(null);
        if (r == null) {
            log.warn("FulfillmentCompleted for order {} without a reservation (ignored)", orderId);
            return Result.NOT_FOUND;
        }
        if (!r.getStatus().holdsStock()) {
            log.warn("FulfillmentCompleted for order {} but reservation is {}: nothing shipped from stock", orderId,
                    r.getStatus());
            return Result.REJECTED;
        }
        Instant now = clock.instant();
        if (reservations.markShipped(r.getId(), now) != 1) {
            return Result.NO_OP;   // already shipped
        }
        List<ReservationItem> items = reservationItems.findByReservationIdOrderByProductIdAsc(r.getId());
        String actor = Actors.current();
        for (ReservationItem i : items) {
            if (stock.ship(i.getProductId(), i.getQuantity(), now) != 1) {
                throw new IllegalStateException("Cannot ship " + i.getQuantity() + " of product " + i.getProductId()
                        + ": stock books are inconsistent");
            }
            movements.save(new StockMovement(i.getProductId(), MovementType.SHIP, -i.getQuantity(), -i.getQuantity(),
                    null, null, "shipped", orderId.toString(), actor, now));
        }
        cache.evictAfterCommit(items.stream().map(ReservationItem::getProductId).toList());
        audit.record("Reservation", r.getId(), "SHIPPED", Map.of("orderId", orderId));
        TxHooks.afterCommit(() -> metrics.reservation("shipped"));
        return Result.APPLIED;
    }

    /**
     * Expires every RESERVED reservation past its TTL (batch-limited, one transaction each so one bad row never blocks
     * the rest). Safe to run on several instances at once: the guarded UPDATE lets exactly one of them win a row.
     *
     * @return number of reservations expired by THIS call
     */
    public int expireDue() {
        List<UUID> ids = reservations.findExpiredIds(clock.instant(), PageRequest.of(0, props.getExpiryBatchSize()));
        int expired = 0;
        for (UUID id : ids) {
            try {
                if (Boolean.TRUE.equals(tx.execute(status -> expireOne(id)))) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not expire reservation {} (will retry on the next scan): {}", id, e.toString());
            }
        }
        return expired;
    }

    private boolean expireOne(UUID reservationId) {
        Instant now = clock.instant();
        if (reservations.markExpired(reservationId, now) != 1) {
            return false;   // committed, released or expired by someone else in the meantime
        }
        Reservation r = reservations.findById(reservationId).orElseThrow();
        giveBackStock(reservationId, r.getOrderId(), MovementType.EXPIRE, "reservation TTL elapsed", now);
        events.released(r.getOrderId(), reservationId, com.enterprise.oms.common.events.EventPayloads.ReleaseReasons.EXPIRED,
                CorrelationIds.newId(), null);
        audit.record("system", "Reservation", reservationId, "EXPIRED", Map.of("orderId", r.getOrderId()));
        TxHooks.afterCommit(() -> metrics.reservation("expired"));
        log.info("Reservation expired: orderId={} reservationId={}", r.getOrderId(), reservationId);
        return true;
    }

    /** Re-reads the order's reservation bypassing the persistence-context cache (a JPQL query returns the already-managed, possibly stale instance). */
    private Reservation fresh(UUID orderId) {
        em.flush();
        em.clear();
        return reservations.findByOrderId(orderId).orElse(null);
    }

    private void giveBackStock(UUID reservationId, UUID orderId, MovementType type, String reason, Instant now) {
        List<ReservationItem> items = reservationItems.findByReservationIdOrderByProductIdAsc(reservationId);
        String actor = Actors.current();
        for (ReservationItem i : items) {
            if (stock.release(i.getProductId(), i.getQuantity(), now) != 1) {
                throw new IllegalStateException("Cannot give back " + i.getQuantity() + " of product "
                        + i.getProductId() + ": reserved count is lower (stock books are inconsistent)");
            }
            movements.save(new StockMovement(i.getProductId(), type, 0, -i.getQuantity(), null, null, reason,
                    orderId.toString(), actor, now));
        }
        cache.evictAfterCommit(items.stream().map(ReservationItem::getProductId).toList());
    }
}

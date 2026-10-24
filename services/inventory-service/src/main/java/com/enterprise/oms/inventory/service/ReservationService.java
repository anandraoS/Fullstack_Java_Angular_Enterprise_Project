package com.enterprise.oms.inventory.service;

import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.common.events.EventPayloads.QuantityItem;
import com.enterprise.oms.common.events.EventPayloads.Shortage;
import com.enterprise.oms.inventory.cache.ProductCacheInvalidator;
import com.enterprise.oms.inventory.config.InventoryProperties;
import com.enterprise.oms.inventory.domain.MovementType;
import com.enterprise.oms.inventory.domain.Product;
import com.enterprise.oms.inventory.domain.Reservation;
import com.enterprise.oms.inventory.domain.ReservationItem;
import com.enterprise.oms.inventory.domain.ReservationStatus;
import com.enterprise.oms.inventory.domain.StockMovement;
import com.enterprise.oms.inventory.metrics.InventoryMetrics;
import com.enterprise.oms.inventory.repo.ProductRepository;
import com.enterprise.oms.inventory.repo.ReservationClaims;
import com.enterprise.oms.inventory.repo.ReservationItemRepository;
import com.enterprise.oms.inventory.repo.ReservationRepository;
import com.enterprise.oms.inventory.repo.StockMovementRepository;
import com.enterprise.oms.inventory.reservation.ReservationOutcome;
import com.enterprise.oms.inventory.reservation.ReservationStrategies;
import com.enterprise.oms.inventory.reservation.ReservationStrategy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The reservation core (contract section 7).
 * <ol>
 *   <li>normalize the request (merge lines, sort by productId = global lock order);</li>
 *   <li>duplicate protection: the order's single reservation slot is CLAIMED first
 *       ({@code INSERT ... ON CONFLICT (order_id) DO NOTHING}); a duplicate gets the ORIGINAL outcome back;</li>
 *   <li>catalog check (unknown / inactive product -> FAILED without touching stock);</li>
 *   <li>the configured {@link ReservationStrategy} takes the stock, all-or-nothing;</li>
 *   <li>reservation items, stock movements, audit and the outbox event are written in the SAME transaction
 *       (which, for a Kafka command, is also the processed_events transaction).</li>
 * </ol>
 * Joins the caller's transaction. A thrown exception rolls everything back, including the slot claim, so the command
 * can be retried from scratch.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final int MAX_LINES = 500;
    private static final int MAX_QUANTITY = 1_000_000;
    private static final TypeReference<List<Shortage>> SHORTAGES = new TypeReference<>() { };

    public static final String REASON_INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
    public static final String REASON_UNKNOWN_PRODUCT = "UNKNOWN_PRODUCT";
    public static final String REASON_PRODUCT_INACTIVE = "PRODUCT_INACTIVE";

    private final ReservationRepository reservations;
    private final ReservationClaims claims;
    private final ReservationItemRepository reservationItems;
    private final ProductRepository products;
    private final StockMovementRepository movements;
    private final ReservationStrategies strategies;
    private final InventoryEventPublisher events;
    private final AuditService audit;
    private final InventoryProperties props;
    private final InventoryMetrics metrics;
    private final ProductCacheInvalidator cache;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ReservationService(ReservationRepository reservations, ReservationClaims claims,
                              ReservationItemRepository reservationItems,
                              ProductRepository products, StockMovementRepository movements,
                              ReservationStrategies strategies, InventoryEventPublisher events, AuditService audit,
                              InventoryProperties props, InventoryMetrics metrics, ProductCacheInvalidator cache,
                              ObjectMapper mapper, Clock clock) {
        this.reservations = reservations;
        this.claims = claims;
        this.reservationItems = reservationItems;
        this.products = products;
        this.movements = movements;
        this.strategies = strategies;
        this.events = events;
        this.audit = audit;
        this.props = props;
        this.metrics = metrics;
        this.cache = cache;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * @param ttlSeconds null or &lt;= 0 means the configured default; capped at {@code max-reservation-ttl-seconds}
     * @throws IllegalArgumentException for malformed requests (non-retryable: dead-lettered when it comes from Kafka)
     */
    @Transactional
    public ReservationOutcome reserve(UUID orderId, List<QuantityItem> items, Integer ttlSeconds, String correlationId,
                                      UUID causationId) {
        ReservationStrategy strategy = strategies.active();
        Timer.Sample sample = metrics.start();
        String outcomeTag = "error";
        try {
            List<QuantityItem> lines = normalize(orderId, items);
            ReservationOutcome outcome = doReserve(orderId, lines, ttlSeconds, correlationId, causationId, strategy);
            outcomeTag = outcome.duplicate() ? "duplicate" : outcome.failed() ? "failed" : "reserved";
            String tag = outcomeTag;
            TxHooks.afterCommit(() -> metrics.reservation(tag));
            return outcome;
        } finally {
            metrics.stop(sample, strategy.name(), outcomeTag);
        }
    }

    private ReservationOutcome doReserve(UUID orderId, List<QuantityItem> lines, Integer ttlSeconds,
                                         String correlationId, UUID causationId, ReservationStrategy strategy) {
        Optional<Reservation> existing = reservations.findByOrderId(orderId);
        if (existing.isPresent()) {
            return replay(existing.get(), correlationId, causationId);
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plusSeconds(effectiveTtl(ttlSeconds));
        UUID reservationId = UUID.randomUUID();
        if (!claims.claimReserved(reservationId, orderId, expiresAt, now)) {
            // a concurrent transaction claimed the slot first and has committed by now (the insert blocked on it)
            Reservation winner = reservations.findByOrderId(orderId).orElseThrow(() ->
                    new IllegalStateException("Reservation slot of order " + orderId + " vanished"));
            return replay(winner, correlationId, causationId);
        }

        List<UUID> ids = lines.stream().map(QuantityItem::productId).toList();
        Map<UUID, Product> catalog = products.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<Shortage> missing = lines.stream().filter(l -> !catalog.containsKey(l.productId()))
                .map(l -> new Shortage(l.productId(), l.quantity(), 0)).toList();
        if (!missing.isEmpty()) {
            return fail(reservationId, orderId, lines, REASON_UNKNOWN_PRODUCT, missing, now, correlationId, causationId);
        }
        List<Shortage> inactive = lines.stream().filter(l -> !catalog.get(l.productId()).isActive())
                .map(l -> new Shortage(l.productId(), l.quantity(), 0)).toList();
        if (!inactive.isEmpty()) {
            return fail(reservationId, orderId, lines, REASON_PRODUCT_INACTIVE, inactive, now, correlationId, causationId);
        }

        ReservationStrategy.Result result = strategy.tryReserve(lines);
        if (!result.success()) {
            return fail(reservationId, orderId, lines, REASON_INSUFFICIENT_STOCK, result.shortages(), now,
                    correlationId, causationId);
        }

        String actor = Actors.current();
        for (QuantityItem l : lines) {
            reservationItems.save(new ReservationItem(reservationId, l.productId(), l.quantity()));
            movements.save(new StockMovement(l.productId(), MovementType.RESERVE, 0, l.quantity(), null, null,
                    "reservation", orderId.toString(), actor, now));
        }
        events.reserved(orderId, reservationId, lines, expiresAt, correlationId, causationId);
        audit.record("Reservation", reservationId, "RESERVED",
                Map.of("orderId", orderId, "lines", lines.size(), "strategy", strategy.name()));
        cache.evictAfterCommit(ids);
        log.info("Reserved stock: orderId={} reservationId={} lines={} strategy={}", orderId, reservationId,
                lines.size(), strategy.name());
        return new ReservationOutcome(orderId, reservationId, ReservationStatus.RESERVED, expiresAt, lines, null,
                List.of(), false);
    }

    private ReservationOutcome fail(UUID reservationId, UUID orderId, List<QuantityItem> lines, String reason,
                                    List<Shortage> shortages, Instant now, String correlationId, UUID causationId) {
        reservations.markFailed(reservationId, reason, toJson(shortages), now);
        for (QuantityItem l : lines) {
            reservationItems.save(new ReservationItem(reservationId, l.productId(), l.quantity()));
        }
        events.failed(orderId, reason, shortages, correlationId, causationId);
        audit.record("Reservation", reservationId, "FAILED",
                Map.of("orderId", orderId, "reason", reason, "shortages", shortages.size()));
        log.info("Reservation refused: orderId={} reason={} shortages={}", orderId, reason, shortages.size());
        return new ReservationOutcome(orderId, reservationId, ReservationStatus.FAILED, null, lines, reason, shortages,
                false);
    }

    /** Rebuilds the ORIGINAL outcome from the stored row and re-emits the matching event (idempotent for consumers). */
    private ReservationOutcome replay(Reservation r, String correlationId, UUID causationId) {
        List<QuantityItem> lines = reservationItems.findByReservationIdOrderByProductIdAsc(r.getId()).stream()
                .map(i -> new QuantityItem(i.getProductId(), i.getQuantity())).toList();
        ReservationStatus status = r.getStatus();
        ReservationOutcome out;
        switch (status) {
            case FAILED -> {
                List<Shortage> shortages = fromJson(r.getFailureDetail());
                events.failed(r.getOrderId(), r.getFailureReason(), shortages, correlationId, causationId);
                out = new ReservationOutcome(r.getOrderId(), r.getId(), status, null, lines, r.getFailureReason(),
                        shortages, true);
            }
            case RELEASED, EXPIRED -> {
                events.released(r.getOrderId(), r.getId(), r.getReleaseReason(), correlationId, causationId);
                out = new ReservationOutcome(r.getOrderId(), r.getId(), status, null, lines, r.getReleaseReason(),
                        List.of(), true);
            }
            default -> {
                events.reserved(r.getOrderId(), r.getId(), lines, r.getExpiresAt(), correlationId, causationId);
                out = new ReservationOutcome(r.getOrderId(), r.getId(), status, r.getExpiresAt(), lines, null,
                        List.of(), true);
            }
        }
        log.info("Duplicate reserve request: orderId={} originalStatus={}", r.getOrderId(), status);
        return out;
    }

    private List<QuantityItem> normalize(UUID orderId, List<QuantityItem> items) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId is required");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        TreeMap<UUID, Integer> merged = new TreeMap<>();
        for (QuantityItem i : items) {
            if (i == null || i.productId() == null) {
                throw new IllegalArgumentException("item productId is required");
            }
            if (i.quantity() < 1 || i.quantity() > MAX_QUANTITY) {
                throw new IllegalArgumentException("item quantity must be between 1 and " + MAX_QUANTITY);
            }
            merged.merge(i.productId(), i.quantity(), Integer::sum);
        }
        if (merged.size() > MAX_LINES) {
            throw new IllegalArgumentException("too many distinct products (max " + MAX_LINES + ")");
        }
        List<QuantityItem> lines = new ArrayList<>(merged.size());
        merged.forEach((p, q) -> lines.add(new QuantityItem(p, q)));
        return List.copyOf(lines);
    }

    private int effectiveTtl(Integer requested) {
        int ttl = requested == null || requested <= 0 ? props.getReservationTtlSeconds() : requested;
        return Math.min(ttl, props.getMaxReservationTtlSeconds());
    }

    private String toJson(List<Shortage> shortages) {
        try {
            return mapper.writeValueAsString(shortages);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize shortages", e);
        }
    }

    private List<Shortage> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, SHORTAGES);
        } catch (JsonProcessingException e) {
            log.warn("Unreadable stored shortage detail, reporting none");
            return List.of();
        }
    }
}

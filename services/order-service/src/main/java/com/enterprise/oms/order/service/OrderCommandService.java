package com.enterprise.oms.order.service;

import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.common.error.ConflictException;
import com.enterprise.oms.common.error.ForbiddenException;
import com.enterprise.oms.common.error.IdempotencyKeyReusedException;
import com.enterprise.oms.common.error.NotFoundException;
import com.enterprise.oms.common.error.RequestValidationException;
import com.enterprise.oms.common.security.CurrentUser;
import com.enterprise.oms.common.web.CorrelationIds;
import com.enterprise.oms.common.web.IdempotencyKeys;
import com.enterprise.oms.order.api.dto.CreateOrderRequest;
import com.enterprise.oms.order.api.dto.OrderItemRequest;
import com.enterprise.oms.order.api.dto.OrderView;
import com.enterprise.oms.order.entity.Customer;
import com.enterprise.oms.order.entity.CustomerOrder;
import com.enterprise.oms.order.entity.IdempotencyKeyRecord;
import com.enterprise.oms.order.entity.SagaInstance;
import com.enterprise.oms.order.repository.CustomerRepository;
import com.enterprise.oms.order.repository.IdempotencyKeyRepository;
import com.enterprise.oms.order.repository.OrderRepository;
import com.enterprise.oms.order.repository.SagaInstanceRepository;
import com.enterprise.oms.order.saga.OrderAccepted;
import com.enterprise.oms.order.saga.OrderSagaOrchestrator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Write side of the orders API: idempotent creation and cancellation.
 *
 * <h3>Idempotent create (POST /orders)</h3>
 * One transaction: (1) look the (customer scope, key) up in {@code idempotency_keys}; found -> compare the request
 * fingerprint and REPLAY the stored response snapshot (same body -> 202 + {@code Idempotent-Replay}; different body ->
 * 422 IDEMPOTENCY_KEY_REUSED); (2) otherwise INSERT the key row first (unique(scope, idem_key)) so a concurrent
 * duplicate blocks on the unique index instead of creating a second order; (3) create order + items + saga +
 * history + audit; (4) store the response snapshot on the key row; commit. The loser of a race gets a unique-violation
 * (or a lock timeout), re-reads the committed row and replays it: exactly one order exists.
 */
@Service
public class OrderCommandService {

    private static final Logger log = LoggerFactory.getLogger(OrderCommandService.class);
    private static final int MAX_CREATE_ATTEMPTS = 3;

    public record CreateResult(OrderView order, boolean replay) {
    }

    private final CustomerRepository customers;
    private final OrderRepository orders;
    private final SagaInstanceRepository sagas;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final OrderTransitions transitions;
    private final OrderSagaOrchestrator saga;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;
    private final RetryingTransactions retryTx;
    private final TransactionTemplate tx;

    public OrderCommandService(CustomerRepository customers, OrderRepository orders, SagaInstanceRepository sagas,
                               IdempotencyKeyRepository idempotencyKeys, OrderTransitions transitions,
                               OrderSagaOrchestrator saga, AuditService audit, ApplicationEventPublisher events,
                               ObjectMapper mapper, RetryingTransactions retryTx, PlatformTransactionManager tm) {
        this.customers = customers;
        this.orders = orders;
        this.sagas = sagas;
        this.idempotencyKeys = idempotencyKeys;
        this.transitions = transitions;
        this.saga = saga;
        this.audit = audit;
        this.events = events;
        this.mapper = mapper;
        this.retryTx = retryTx;
        this.tx = new TransactionTemplate(tm);
    }

    public CreateResult create(CreateOrderRequest request, String idempotencyKeyHeader, CurrentUser user) {
        String key = IdempotencyKeys.require(idempotencyKeyHeader);
        authorizeCustomer(request.customerId(), user);
        rejectDuplicateProducts(request);
        String scope = request.customerId().toString();
        String hash = IdempotencyKeys.fingerprint(request.canonical());

        for (int attempt = 1; attempt <= MAX_CREATE_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> doCreate(request, scope, key, hash));
            } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
                // a concurrent request with the same key won the race (or a lock wait timed out)
                Optional<CreateResult> replay = tx.execute(status -> idempotencyKeys.findByScopeAndKey(scope, key)
                        .map(r -> replayOf(r, hash)));
                if (replay != null && replay.isPresent()) {
                    log.info("Concurrent duplicate create resolved as replay (key scope {})", scope);
                    return replay.get();
                }
                log.debug("Create attempt {} failed without a committed winner: {}", attempt, e.toString());
            }
        }
        throw new ConflictException("The request could not be processed; retry with the same Idempotency-Key");
    }

    private CreateResult doCreate(CreateOrderRequest request, String scope, String key, String hash) {
        Optional<IdempotencyKeyRecord> existing = idempotencyKeys.findByScopeAndKey(scope, key);
        if (existing.isPresent()) {
            return replayOf(existing.get(), hash);
        }
        Customer customer = customers.findById(request.customerId())
                .orElseThrow(() -> new NotFoundException("Customer", request.customerId()));
        Instant now = Instant.now();
        IdempotencyKeyRecord record = idempotencyKeys.saveAndFlush(new IdempotencyKeyRecord(scope, key, hash, now));

        CustomerOrder order = new CustomerOrder(UUID.randomUUID(), customer.getId(), customer.getEmail(),
                request.shippingAddress().toEntity(), CorrelationIds.currentOrCreate(), now);
        for (OrderItemRequest item : request.items()) {
            order.addItem(item.productId(), item.quantity());
        }
        orders.save(order);
        saga.start(order);
        transitions.recordCreation(order, Actors.current());
        events.publishEvent(new OrderAccepted(order.getId()));   // handled after commit (SagaTrigger)

        OrderView view = OrderView.of(order);
        record.complete(order.getId(), 202, toJson(view));
        return new CreateResult(view, false);
    }

    private CreateResult replayOf(IdempotencyKeyRecord record, String hash) {
        if (!record.getRequestHash().equals(hash)) {
            throw new IdempotencyKeyReusedException();
        }
        if (record.getResponseBody() == null) {
            throw new ConflictException("The original request is still being processed; retry shortly");
        }
        try {
            return new CreateResult(mapper.readValue(record.getResponseBody(), OrderView.class), true);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotency snapshot is unreadable", e);
        }
    }

    private String toJson(OrderView view) {
        try {
            return mapper.writeValueAsString(view);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize order snapshot", e);
        }
    }

    /** CUSTOMER tokens may only order for their own customerId; mismatch is 403 (the id came from the caller). */
    private static void authorizeCustomer(UUID bodyCustomerId, CurrentUser user) {
        if (user.isStaff()) {
            return;
        }
        if (!user.isCustomer() || user.customerId() == null || !user.customerId().equals(bodyCustomerId)) {
            throw new ForbiddenException("customerId does not match the authenticated customer");
        }
    }

    private static void rejectDuplicateProducts(CreateOrderRequest request) {
        Set<UUID> seen = new HashSet<>();
        for (OrderItemRequest item : request.items()) {
            if (!seen.add(item.productId())) {
                throw new RequestValidationException("items", "duplicate productId " + item.productId());
            }
        }
    }

    // ---- cancel -------------------------------------------------------------------------------------------------

    /**
     * Cancels an order the caller may see (others' orders are 404). Retried on optimistic-lock conflicts so a cancel
     * racing a saga event re-evaluates against the winner's state.
     */
    public OrderView cancel(UUID orderId, String reason, CurrentUser user) {
        String text = reason == null || reason.isBlank()
                ? (user.isCustomer() ? "Cancelled by customer" : "Cancelled by staff") : reason.trim();
        return retryTx.run(() -> {
            CustomerOrder order = orders.findWithItemsById(orderId)
                    .filter(o -> user.canAccessCustomer(o.getCustomerId()))
                    .orElseThrow(() -> new NotFoundException("Order", orderId));
            SagaInstance sagaInstance = sagas.findByOrderId(orderId)
                    .orElseThrow(() -> new NotFoundException("Saga", orderId));
            saga.cancel(order, sagaInstance, text);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reason", text);
            detail.put("resultingStatus", order.getStatus().name());
            audit.record("Order", orderId, "ORDER_CANCEL_REQUESTED", detail);
            orders.flush();   // surface a version conflict now, and return the post-update version
            return OrderView.of(order);
        });
    }
}

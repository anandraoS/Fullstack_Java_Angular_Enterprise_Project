package com.enterprise.oms.order.saga;

import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.common.events.EventPayloads.ReleaseReasons;
import com.enterprise.oms.order.config.ReconciliationProperties;
import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.domain.SagaState;
import com.enterprise.oms.order.domain.SagaStep;
import com.enterprise.oms.order.domain.StepStatus;
import com.enterprise.oms.order.entity.CustomerOrder;
import com.enterprise.oms.order.entity.SagaInstance;
import com.enterprise.oms.order.entity.SagaStepLog;
import com.enterprise.oms.order.repository.OrderRepository;
import com.enterprise.oms.order.repository.SagaInstanceRepository;
import com.enterprise.oms.order.repository.SagaStepLogRepository;
import com.enterprise.oms.order.service.Actors;
import com.enterprise.oms.order.service.OrderMetrics;
import com.enterprise.oms.order.service.RetryingTransactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Finds and repairs inconsistencies BETWEEN order-service and the services it orchestrates (contract section 8).
 *
 * <p><b>What it can see</b> (only order-service's own database): each saga knows the reservation id it was told about
 * ({@code InventoryReserved}), whether {@code InventoryReleased} ever came back, and whether any
 * {@code FulfillmentStarted} came back. From that it detects and repairs:
 * <ol>
 *   <li>a terminal order (CANCELLED / FAILED / REJECTED path) whose reservation was never confirmed released
 *       (covers "order terminal but reservation not released" and "CANCELLED order with reservation RESERVED") ->
 *       re-issues the idempotent {@code ReleaseInventory};</li>
 *   <li>a CONFIRMED order with no fulfillment progress -> re-issues {@code StartFulfillment};</li>
 *   <li>a compensation flagged NEEDS_ATTENTION (retries exhausted) -> re-opens it and re-issues the release.</li>
 * </ol>
 * <b>What it cannot see</b>: the actual state inside inventory (reservations, stock) and fulfillment. It repairs by
 * re-sending idempotent commands, never by reading or writing their data; if the other side is the one that is wrong
 * (e.g. a reservation inventory never told us about because the reply was lost AND the command was never processed),
 * the order-side symptom is the only trigger, and a real fix needs inventory's own reservation/expiry jobs.
 * Only inconsistencies older than {@code oms.reconciliation.min-age-seconds} are touched so in-flight work is left alone.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final SagaInstanceRepository sagas;
    private final OrderRepository orders;
    private final SagaStepLogRepository stepLog;
    private final SagaMessaging messaging;
    private final AuditService audit;
    private final OrderMetrics metrics;
    private final RetryingTransactions retryTx;
    private final ReconciliationProperties props;
    private final TransactionTemplate readOnlyTx;

    public ReconciliationService(SagaInstanceRepository sagas, OrderRepository orders, SagaStepLogRepository stepLog,
                                 SagaMessaging messaging, AuditService audit, OrderMetrics metrics,
                                 RetryingTransactions retryTx, ReconciliationProperties props,
                                 PlatformTransactionManager tm) {
        this.sagas = sagas;
        this.orders = orders;
        this.stepLog = stepLog;
        this.messaging = messaging;
        this.audit = audit;
        this.metrics = metrics;
        this.retryTx = retryTx;
        this.props = props;
        this.readOnlyTx = new TransactionTemplate(tm);
        this.readOnlyTx.setReadOnly(true);
    }

    @Scheduled(fixedDelayString = "${oms.reconciliation.interval-ms:300000}",
            initialDelayString = "${oms.reconciliation.interval-ms:300000}")
    public void scheduledRun() {
        if (!props.isEnabled()) {
            return;
        }
        try {
            ReconciliationResult r = run(Actors.SYSTEM);
            if (r.repaired() > 0) {
                log.info("Scheduled reconciliation repaired {} of {} examined", r.repaired(), r.examined());
            }
        } catch (RuntimeException e) {
            log.warn("Scheduled reconciliation failed (will retry): {}", e.toString());
        }
    }

    public ReconciliationResult run() {
        return run(Actors.current());
    }

    public ReconciliationResult run(String actor) {
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(props.getMinAgeSeconds()));
        PageRequest page = PageRequest.of(0, props.getBatchSize());
        List<String> details = new ArrayList<>();
        int examined = 0;
        int repaired = 0;

        List<UUID> unreleased = readOnlyTx.execute(s -> sagas.findWithUnreleasedReservation(
                List.of(SagaState.ABORTED, SagaState.COMPENSATED, SagaState.FAILED), cutoff, page)
                .stream().map(SagaInstance::getOrderId).toList());
        for (UUID id : unreleased) {
            examined++;
            Optional<String> fixed = repair(id, actor, this::repairUnreleasedReservation);
            if (fixed.isPresent()) {
                repaired++;
                details.add(fixed.get());
            }
        }

        List<UUID> noProgress = readOnlyTx.execute(s -> sagas.findStalled(List.of(SagaState.RUNNING), cutoff, page)
                .stream().filter(sg -> sg.getCurrentStep() == SagaStep.FULFILL && !sg.isFulfillmentStarted())
                .map(SagaInstance::getOrderId).toList());
        for (UUID id : noProgress) {
            examined++;
            Optional<String> fixed = repair(id, actor, this::repairNoFulfillmentProgress);
            if (fixed.isPresent()) {
                repaired++;
                details.add(fixed.get());
            }
        }

        List<UUID> flagged = readOnlyTx.execute(s -> sagas.findStalled(List.of(SagaState.NEEDS_ATTENTION), cutoff, page)
                .stream().map(SagaInstance::getOrderId).toList());
        for (UUID id : flagged) {
            examined++;
            Optional<String> fixed = repair(id, actor, this::repairFlaggedCompensation);
            if (fixed.isPresent()) {
                repaired++;
                details.add(fixed.get());
            }
        }

        ReconciliationResult result = new ReconciliationResult(examined, repaired, List.copyOf(details));
        if (examined > 0 || !Actors.SYSTEM.equals(actor)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("examined", examined);
            detail.put("repaired", repaired);
            audit.record(actor, "Reconciliation", "run-" + Instant.now().toEpochMilli(), "RECONCILIATION_RUN", detail);
        }
        return result;
    }

    @FunctionalInterface
    private interface Repair {
        Optional<String> apply(CustomerOrder order, SagaInstance saga, String actor);
    }

    private Optional<String> repair(UUID orderId, String actor, Repair repair) {
        try {
            return retryTx.run(() -> {
                Optional<CustomerOrder> order = orders.findWithItemsById(orderId);
                Optional<SagaInstance> saga = sagas.findByOrderId(orderId);
                if (order.isEmpty() || saga.isEmpty()) {
                    return Optional.<String>empty();
                }
                return repair.apply(order.get(), saga.get(), actor);
            });
        } catch (RuntimeException e) {
            log.warn("Reconciliation of order {} failed: {}", orderId, e.toString());
            return Optional.empty();
        }
    }

    private Optional<String> repairUnreleasedReservation(CustomerOrder order, SagaInstance saga, String actor) {
        if (saga.isReservationReleased() || saga.getReservationId() == null || !saga.getState().isTerminal()
                || saga.getState() == SagaState.COMPLETED) {
            return Optional.empty(); // fixed meanwhile
        }
        String reason = ReleaseReasons.FULFILLMENT_FAILURE.equals(saga.getReason())
                ? ReleaseReasons.FULFILLMENT_FAILURE : ReleaseReasons.CANCELLATION;
        messaging.releaseInventory(order, reason, null);
        saga.touch(Instant.now());
        stepLog.save(new SagaStepLog(saga.getId(), order.getId(), SagaStep.RELEASE_INVENTORY, StepStatus.RETRIED,
                saga.getAttempts() + 1, "reconciliation: reservation " + saga.getReservationId()
                + " was never confirmed released; ReleaseInventory re-issued", Instant.now()));
        metrics.compensation("RECONCILIATION");
        return Optional.of(done(order, actor, "UNRELEASED_RESERVATION",
                "order " + order.getId() + " is " + order.getStatus() + " but reservation "
                        + saga.getReservationId() + " was not released: ReleaseInventory(" + reason + ") re-issued"));
    }

    private Optional<String> repairNoFulfillmentProgress(CustomerOrder order, SagaInstance saga, String actor) {
        if (order.getStatus() != OrderStatus.CONFIRMED || saga.isFulfillmentStarted()) {
            return Optional.empty();
        }
        messaging.startFulfillment(order, null);
        saga.touch(Instant.now());
        stepLog.save(new SagaStepLog(saga.getId(), order.getId(), SagaStep.FULFILL, StepStatus.RETRIED,
                saga.getAttempts() + 1, "reconciliation: no fulfillment progress; StartFulfillment re-issued",
                Instant.now()));
        return Optional.of(done(order, actor, "NO_FULFILLMENT_PROGRESS", "order " + order.getId()
                + " is CONFIRMED without fulfillment progress: StartFulfillment re-issued"));
    }

    private Optional<String> repairFlaggedCompensation(CustomerOrder order, SagaInstance saga, String actor) {
        if (saga.getState() != SagaState.NEEDS_ATTENTION || order.getStatus() != OrderStatus.COMPENSATION_PENDING) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        saga.resetAttempts(now);
        saga.setState(SagaState.COMPENSATING, now);
        String reason = saga.getReason() != null ? saga.getReason() : ReleaseReasons.CANCELLATION;
        messaging.releaseInventory(order, reason, null);
        stepLog.save(new SagaStepLog(saga.getId(), order.getId(), SagaStep.RELEASE_INVENTORY, StepStatus.RETRIED, 1,
                "reconciliation: flagged compensation re-opened; ReleaseInventory re-issued", now));
        metrics.compensation("RECONCILIATION");
        return Optional.of(done(order, actor, "COMPENSATION_STUCK", "order " + order.getId()
                + " had a stuck compensation: re-opened and ReleaseInventory(" + reason + ") re-issued"));
    }

    private String done(CustomerOrder order, String actor, String issue, String description) {
        audit.record(actor, "Order", order.getId(), "ORDER_RECONCILED", Map.of("issue", issue));
        return description;
    }
}

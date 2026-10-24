package com.enterprise.oms.order.saga;

import com.enterprise.oms.order.config.SagaProperties;
import com.enterprise.oms.order.domain.SagaState;
import com.enterprise.oms.order.entity.SagaInstance;
import com.enterprise.oms.order.repository.SagaInstanceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Re-drives sagas that made no progress for {@code oms.saga.stuck-after-seconds}: a lost command, a lost reply, a
 * crash between steps, an inventory outage. Each re-drive re-sends the current step's idempotent command; after
 * {@code oms.saga.max-attempts} the saga fails (non-compensable step) or is flagged for an operator (compensation).
 * Safe on several instances at once: the saga row is {@code @Version}ed and every command is idempotent.
 */
@Component
public class SagaRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(SagaRecoveryJob.class);

    private final SagaInstanceRepository sagas;
    private final OrderSagaOrchestrator orchestrator;
    private final SagaProperties props;
    private final TransactionTemplate tx;
    private final MeterRegistry registry;

    public SagaRecoveryJob(SagaInstanceRepository sagas, OrderSagaOrchestrator orchestrator, SagaProperties props,
                           org.springframework.transaction.PlatformTransactionManager tm, MeterRegistry registry) {
        this.sagas = sagas;
        this.orchestrator = orchestrator;
        this.props = props;
        this.tx = new TransactionTemplate(tm);
        this.tx.setReadOnly(true);
        this.registry = registry;
    }

    @Scheduled(fixedDelayString = "${oms.saga.recovery-interval-ms:10000}",
            initialDelayString = "${oms.saga.recovery-interval-ms:10000}")
    public void scheduledRun() {
        if (!props.isRecoveryEnabled()) {
            return;
        }
        try {
            recoverStuck();
        } catch (RuntimeException e) {
            log.warn("Saga recovery cycle failed (will retry): {}", e.toString());
        }
    }

    /** One pass; returns the number of sagas that were re-driven. */
    public int recoverStuck() {
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(props.getStuckAfterSeconds()));
        List<UUID> stuck = tx.execute(s -> sagas.findStalled(List.of(SagaState.RUNNING, SagaState.COMPENSATING), cutoff,
                PageRequest.of(0, props.getRecoveryBatchSize())).stream().map(SagaInstance::getOrderId).toList());
        int redriven = 0;
        if (stuck == null) {
            return 0;
        }
        for (UUID orderId : stuck) {
            try {
                OrderSagaOrchestrator.RedriveResult r = orchestrator.recover(orderId, false);
                if (r != OrderSagaOrchestrator.RedriveResult.SKIPPED) {
                    redriven++;
                    registry.counter("oms.saga.recovered", "result", r.name()).increment();
                    log.info("Saga of order {} re-driven by recovery job: {}", orderId, r);
                }
            } catch (RuntimeException e) {
                log.warn("Recovery of order {} failed: {}", orderId, e.toString());
            }
        }
        return redriven;
    }
}

package com.enterprise.oms.order.saga;

import com.enterprise.oms.order.config.SagaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts the first saga step (validation) once the order is safely committed, on a separate thread so the HTTP
 * request returns 202 immediately. If this hand-off is lost (crash, rejected task) nothing is lost: the order stays
 * CREATED and the SagaRecoveryJob re-drives it.
 */
@Component
public class SagaTrigger {

    private static final Logger log = LoggerFactory.getLogger(SagaTrigger.class);

    private final OrderSagaOrchestrator orchestrator;
    private final ThreadPoolTaskExecutor executor;
    private final SagaProperties props;

    public SagaTrigger(OrderSagaOrchestrator orchestrator,
                       @org.springframework.beans.factory.annotation.Qualifier("omsSagaExecutor") ThreadPoolTaskExecutor executor,
                       SagaProperties props) {
        this.orchestrator = orchestrator;
        this.executor = executor;
        this.props = props;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderAccepted(OrderAccepted event) {
        if (!props.isAutoStart()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    orchestrator.validate(event.orderId());
                } catch (RuntimeException e) {
                    log.warn("Validation of order {} failed unexpectedly; the recovery job will retry: {}",
                            event.orderId(), e.toString());
                }
            });
        } catch (TaskRejectedException e) {
            log.warn("Saga executor saturated; order {} stays CREATED for the recovery job", event.orderId());
        }
    }
}

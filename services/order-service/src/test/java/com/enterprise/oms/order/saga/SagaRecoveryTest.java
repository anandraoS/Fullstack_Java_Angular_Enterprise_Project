package com.enterprise.oms.order.saga;

import com.enterprise.oms.common.error.InvalidStateTransitionException;
import com.enterprise.oms.order.config.SagaProperties;
import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.domain.SagaState;
import com.enterprise.oms.order.domain.SagaStep;
import com.enterprise.oms.order.domain.StepStatus;
import com.enterprise.oms.order.support.AbstractOrderSpringTest;
import com.enterprise.oms.order.support.TestEvents;
import com.enterprise.oms.testsupport.JwtTestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SagaRecoveryJob / orchestrator.recover: re-driving stuck sagas, attempt limits, flagging, manual retry. */
class SagaRecoveryTest extends AbstractOrderSpringTest {

    @Autowired SagaRecoveryJob job;
    @Autowired SagaProperties props;

    private int originalMaxAttempts;
    private long originalStuckAfter;

    @BeforeEach
    void tuneProperties() {
        originalMaxAttempts = props.getMaxAttempts();
        originalStuckAfter = props.getStuckAfterSeconds();
        props.setMaxAttempts(3);
    }

    @AfterEach
    void restoreProperties() {
        props.setMaxAttempts(originalMaxAttempts);
        props.setStuckAfterSeconds(originalStuckAfter);
    }

    private void cancel(UUID id) throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/orders/{id}/cancel", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"recovery test\"}").header("Authorization", JwtTestTokens.admin()));
    }

    private com.enterprise.oms.order.entity.SagaInstance saga(UUID id) {
        return sagas.findByOrderId(id).orElseThrow();
    }

    private double alerts(String kind) {
        var c = meters.find("oms.saga.alerts").tag("kind", kind).counter();
        return c == null ? 0 : c.count();
    }

    // ---- re-sending the current step -----------------------------------------------------------------------------

    @Test
    void aStuckValidatedSagaGetsItsIdempotentReserveCommandResent() throws Exception {
        UUID id = validatedOrder();

        assertThat(orchestrator.recover(id, false)).isEqualTo(OrderSagaOrchestrator.RedriveResult.REDRIVEN);

        assertThat(outboxCount(id, "ReserveInventory")).isEqualTo(2);
        assertThat(saga(id).getAttempts()).isEqualTo(1);
        assertThat(stepLog.findByOrderIdOrderByIdAsc(id)).anyMatch(s -> s.getStep() == SagaStep.RESERVE_INVENTORY
                && s.getStatus() == StepStatus.RETRIED && s.getAttempt() == 1);
        assertThat(auditActions(id)).contains("SAGA_REDRIVEN");
    }

    @Test
    void anOrderStuckInCreatedIsValidatedByTheRecovery() throws Exception {
        UUID id = createdOrder();

        orchestrator.recover(id, false);

        assertThat(statusOf(id)).isEqualTo(OrderStatus.VALIDATED);
        assertThat(outboxTypes(id)).containsExactly("OrderCreated", "ReserveInventory");
    }

    @Test
    void aStuckConfirmedOrderGetsStartFulfillmentResent() throws Exception {
        UUID id = confirmedOrder();
        orchestrator.recover(id, false);
        assertThat(outboxCount(id, "StartFulfillment")).isEqualTo(2);
        assertThat(statusOf(id)).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void aStuckCompensationGetsReleaseInventoryResentWithItsOriginalReason() throws Exception {
        UUID id = confirmedOrder();
        deliverFulfillment(TestEvents.fulfillmentFailed(id, "boom"));
        assertThat(outboxCount(id, "ReleaseInventory")).isEqualTo(1);

        orchestrator.recover(id, false);

        assertThat(outboxCount(id, "ReleaseInventory")).isEqualTo(2);
        assertThat(outboxFor(id).stream().filter(e -> e.getEventType().equals("ReleaseInventory"))
                .allMatch(e -> e.getPayload().contains("FULFILLMENT_FAILURE"))).isTrue();
    }

    @Test
    void terminalOrdersAreSkipped() throws Exception {
        UUID id = confirmedOrder();
        deliverFulfillment(TestEvents.fulfillmentCompleted(id, "TRK"));
        assertThat(orchestrator.recover(id, false)).isEqualTo(OrderSagaOrchestrator.RedriveResult.SKIPPED);
    }

    // ---- exhaustion ----------------------------------------------------------------------------------------------

    @Test
    void validatedSagaThatNeverProgressesFailsAndDefensivelyReleases() throws Exception {
        UUID id = validatedOrder();
        double before = alerts("exhausted-validated");
        for (int i = 0; i < 3; i++) {
            assertThat(orchestrator.recover(id, false)).isEqualTo(OrderSagaOrchestrator.RedriveResult.REDRIVEN);
        }

        assertThat(orchestrator.recover(id, false)).isEqualTo(OrderSagaOrchestrator.RedriveResult.EXHAUSTED);

        assertThat(statusOf(id)).isEqualTo(OrderStatus.FAILED);
        assertThat(saga(id).getState()).isEqualTo(SagaState.FAILED);
        assertThat(outboxCount(id, "OrderFailed")).isEqualTo(1);
        assertThat(outboxCount(id, "ReleaseInventory")).isEqualTo(1);     // the reply may have been lost: undo defensively
        assertThat(alerts("exhausted-validated")).isEqualTo(before + 1);
        assertThat(auditActions(id)).contains("SAGA_EXHAUSTED");
    }

    @Test
    void confirmedSagaWithoutFulfillmentProgressIsTreatedAsAFulfillmentFailure() throws Exception {
        UUID id = confirmedOrder();
        for (int i = 0; i < 3; i++) {
            orchestrator.recover(id, false);
        }

        assertThat(orchestrator.recover(id, false)).isEqualTo(OrderSagaOrchestrator.RedriveResult.EXHAUSTED);

        assertThat(statusOf(id)).isEqualTo(OrderStatus.COMPENSATION_PENDING);
        assertThat(saga(id).getState()).isEqualTo(SagaState.COMPENSATING);
        assertThat(outboxFor(id).stream().filter(e -> e.getEventType().equals("ReleaseInventory")).count()).isEqualTo(1);
        assertThat(order(id).getStatusReason()).contains("No progress after 3 re-drive attempts");
    }

    @Test
    void aCompensationThatCannotBeCompletedIsFlaggedForAnOperator() throws Exception {
        UUID id = confirmedOrder();
        cancel(id);
        assertThat(statusOf(id)).isEqualTo(OrderStatus.COMPENSATION_PENDING);
        double before = alerts("exhausted-compensation_pending");
        for (int i = 0; i < 3; i++) {
            orchestrator.recover(id, false);
        }

        assertThat(orchestrator.recover(id, false)).isEqualTo(OrderSagaOrchestrator.RedriveResult.EXHAUSTED);

        assertThat(statusOf(id)).isEqualTo(OrderStatus.COMPENSATION_PENDING);       // stays, never silently dropped
        assertThat(saga(id).getState()).isEqualTo(SagaState.NEEDS_ATTENTION);
        assertThat(alerts("exhausted-compensation_pending")).isEqualTo(before + 1);
        int releases = (int) outboxCount(id, "ReleaseInventory");

        props.setStuckAfterSeconds(0);
        job.recoverStuck();
        assertThat(outboxCount(id, "ReleaseInventory")).isEqualTo(releases);          // the job leaves flagged sagas alone
    }

    @Test
    void validationOutageEndsFailedAfterTheConfiguredAttempts() throws Exception {
        UUID id = createdOrder();
        inventory.setUnavailable(true);
        try {
            for (int i = 0; i < 3; i++) {
                orchestrator.recover(id, false);
            }
        } finally {
            inventory.setUnavailable(false);
        }
        assertThat(statusOf(id)).isEqualTo(OrderStatus.FAILED);
        assertThat(outboxTypes(id)).containsExactly("OrderFailed");
    }

    // ---- manual retry --------------------------------------------------------------------------------------------

    @Test
    void manualRetryReopensAFlaggedSagaAndResetsAttempts() throws Exception {
        UUID id = confirmedOrder();
        cancel(id);
        for (int i = 0; i < 4; i++) {
            orchestrator.recover(id, false);
        }
        assertThat(saga(id).getState()).isEqualTo(SagaState.NEEDS_ATTENTION);

        assertThat(orchestrator.recover(id, true)).isEqualTo(OrderSagaOrchestrator.RedriveResult.REDRIVEN);

        assertThat(saga(id).getState()).isEqualTo(SagaState.COMPENSATING);
        assertThat(saga(id).getAttempts()).isEqualTo(1);       // reset to 0, then one resend
        assertThat(auditActions(id)).contains("SAGA_REDRIVEN");
    }

    @Test
    void manualRetryOfATerminalOrderIsAConflict() throws Exception {
        UUID id = confirmedOrder();
        deliverFulfillment(TestEvents.fulfillmentCompleted(id, "TRK"));
        assertThatThrownBy(() -> orchestrator.recover(id, true)).isInstanceOf(InvalidStateTransitionException.class);
    }

    // ---- the scheduled job ---------------------------------------------------------------------------------------

    @Test
    void theJobRedrivesOnlySagasThatMadeNoProgressForTheConfiguredTime() throws Exception {
        UUID id = validatedOrder();

        props.setStuckAfterSeconds(3600);
        assertThat(job.recoverStuck()).isZero();                         // everything is far younger than an hour
        assertThat(outboxCount(id, "ReserveInventory")).isEqualTo(1);

        props.setStuckAfterSeconds(0);
        Thread.sleep(20);
        assertThat(job.recoverStuck()).isGreaterThanOrEqualTo(1);
        assertThat(outboxCount(id, "ReserveInventory")).isEqualTo(2);
    }
}

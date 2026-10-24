package com.enterprise.oms.order.saga;

import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.domain.SagaState;
import com.enterprise.oms.order.entity.SagaInstance;
import com.enterprise.oms.order.support.AbstractOrderSpringTest;
import com.enterprise.oms.order.support.TestEvents;
import com.enterprise.oms.testsupport.JwtTestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ReconciliationService: repairs by re-issuing idempotent commands. Assertions identify "our" orders through the
 * result details (the database is shared by the whole test class set).
 */
class ReconciliationTest extends AbstractOrderSpringTest {

    @Autowired ReconciliationService reconciliation;

    @BeforeEach
    void letTimestampsAge() throws InterruptedException {
        Thread.sleep(10);   // min-age is 0 in tests; make sure updatedAt < now
    }

    private boolean repaired(ReconciliationResult r, UUID id) {
        return r.details().stream().anyMatch(d -> d.contains(id.toString()));
    }

    private void cancel(UUID id) throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/orders/{id}/cancel", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"reconcile test\"}").header("Authorization", JwtTestTokens.admin()));
    }

    private SagaInstance saga(UUID id) {
        return sagas.findByOrderId(id).orElseThrow();
    }

    @Test
    void aCancelledOrderWhoseLateReservationWasNeverReleasedGetsANewReleaseCommand() throws Exception {
        UUID id = validatedOrder();
        cancel(id);
        deliverInventory(TestEvents.reserved(id, UUID.randomUUID(), quantityItems(id)));   // release #1 sent, never confirmed
        assertThat(outboxCount(id, "ReleaseInventory")).isEqualTo(1);

        ReconciliationResult r = reconciliation.run("ops-test");

        assertThat(repaired(r, id)).isTrue();
        assertThat(r.repaired()).isGreaterThanOrEqualTo(1);
        assertThat(r.examined()).isGreaterThanOrEqualTo(r.repaired());
        assertThat(outboxCount(id, "ReleaseInventory")).isEqualTo(2);
        assertThat(auditActions(id)).contains("ORDER_RECONCILED");
        assertThat(auditEvents.findAll()).anyMatch(a -> a.getEntityType().equals("Reconciliation")
                && a.getAction().equals("RECONCILIATION_RUN") && a.getActor().equals("ops-test"));

        // once inventory confirms the release the inconsistency is gone
        deliverInventory(TestEvents.released(id, saga(id).getReservationId(), "CANCELLATION"));
        assertThat(repaired(reconciliation.run("ops-test"), id)).isFalse();
    }

    @Test
    void aConfirmedOrderWithoutFulfillmentProgressGetsStartFulfillmentAgain() throws Exception {
        UUID id = confirmedOrder();

        ReconciliationResult r = reconciliation.run("ops-test");

        assertThat(repaired(r, id)).isTrue();
        assertThat(outboxCount(id, "StartFulfillment")).isEqualTo(2);

        deliverFulfillment(TestEvents.fulfillmentStarted(id));
        assertThat(repaired(reconciliation.run("ops-test"), id)).isFalse();
    }

    @Test
    void aFlaggedCompensationIsReopened() throws Exception {
        UUID id = confirmedOrder();
        cancel(id);
        for (int i = 0; i < 6; i++) {
            orchestrator.recover(id, false);
        }
        assertThat(saga(id).getState()).isEqualTo(SagaState.NEEDS_ATTENTION);
        long releases = outboxCount(id, "ReleaseInventory");
        Thread.sleep(10);

        ReconciliationResult r = reconciliation.run("ops-test");

        assertThat(repaired(r, id)).isTrue();
        assertThat(saga(id).getState()).isEqualTo(SagaState.COMPENSATING);
        assertThat(saga(id).getAttempts()).isZero();
        assertThat(outboxCount(id, "ReleaseInventory")).isEqualTo(releases + 1);
        assertThat(statusOf(id)).isEqualTo(OrderStatus.COMPENSATION_PENDING);
    }

    @Test
    void healthyAndFinishedOrdersAreLeftAlone() throws Exception {
        UUID fulfilled = confirmedOrder();
        deliverFulfillment(TestEvents.fulfillmentCompleted(fulfilled, "TRK"));
        UUID compensated = confirmedOrder();
        cancel(compensated);
        deliverInventory(TestEvents.released(compensated, saga(compensated).getReservationId(), "CANCELLATION"));
        UUID rejected = validatedOrder();
        deliverInventory(TestEvents.reservationFailed(rejected, order(rejected).getItems().get(0).getProductId(), 2, 0));
        UUID started = confirmedOrder();
        deliverFulfillment(TestEvents.fulfillmentStarted(started));
        Thread.sleep(10);

        ReconciliationResult r = reconciliation.run("ops-test");

        for (UUID id : new UUID[]{fulfilled, compensated, rejected, started}) {
            assertThat(repaired(r, id)).as("order %s", id).isFalse();
        }
        assertThat(statusOf(fulfilled)).isEqualTo(OrderStatus.FULFILLED);
        assertThat(statusOf(compensated)).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void adminEndpointRunsItAndReturnsTheDocumentedShape() throws Exception {
        UUID id = confirmedOrder();
        Thread.sleep(10);

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/admin/reconciliation/run").header("Authorization", JwtTestTokens.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.examined").isNumber())
                .andExpect(jsonPath("$.repaired").isNumber())
                .andExpect(jsonPath("$.details").isArray());
        assertThat(outboxCount(id, "StartFulfillment")).isEqualTo(2);

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/admin/reconciliation/run").header("Authorization", JwtTestTokens.operator()))
                .andExpect(status().isForbidden());
    }
}

package com.enterprise.oms.order.kafka;

import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.common.events.Topics;
import com.enterprise.oms.common.outbox.OutboxRelay;
import com.enterprise.oms.common.outbox.OutboxService;
import com.enterprise.oms.order.config.SagaProperties;
import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.domain.SagaState;
import com.enterprise.oms.order.entity.Customer;
import com.enterprise.oms.order.saga.ReconciliationResult;
import com.enterprise.oms.order.saga.ReconciliationService;
import com.enterprise.oms.order.saga.SagaRecoveryJob;
import com.enterprise.oms.order.support.StubInventoryClient;
import com.enterprise.oms.order.support.TestEvents;
import com.enterprise.oms.testsupport.Awaits;
import com.enterprise.oms.testsupport.JwtTestTokens;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Failure and recovery behaviour over Kafka: relay outage, stuck sagas, validation outage, reconciliation. */
class OrderResilienceKafkaIT extends AbstractOrderKafkaIT {

    @Autowired OutboxRelay relay;
    @Autowired OutboxService outboxService;
    @Autowired ScheduledAnnotationBeanPostProcessor scheduler;
    @Autowired SagaRecoveryJob recoveryJob;
    @Autowired ReconciliationService reconciliation;
    @Autowired SagaProperties sagaProps;

    private long originalStuckAfter;
    private boolean relayPaused;

    @BeforeEach
    void remember() {
        originalStuckAfter = sagaProps.getStuckAfterSeconds();
    }

    @AfterEach
    void restore() {
        sagaProps.setStuckAfterSeconds(originalStuckAfter);
        inventory.setUnavailable(false);
        if (relayPaused) {
            resumeRelay();
        }
    }

    /** Simulates the relay process being down: cancels ONLY its scheduled polling. Rows keep accumulating. */
    private void pauseRelay() {
        scheduler.postProcessBeforeDestruction(relay, "outboxRelay");
        relayPaused = true;
    }

    private void resumeRelay() {
        scheduler.postProcessAfterInitialization(relay, "outboxRelay");
        relayPaused = false;
    }

    private long pendingFor(UUID orderId) {
        return outbox.findAll().stream().filter(o -> o.getAggregateId().equals(orderId.toString()) && o.getPublishedAt() == null).count();
    }

    // ---- transactional outbox: the commit is the source of truth ----------------------------------------------------

    @Test
    void eventsAppendedWhileTheRelayIsDownAreCommittedAndPublishedLaterInOrder() throws Exception {
        pauseRelay();
        Customer customer = newCustomer();

        UUID id = placeOrder(customer, newProduct(100), 2);
        awaitStatus(id, OrderStatus.VALIDATED);          // state changed and committed ...

        Awaits.untilAsserted(() -> assertThat(pendingFor(id)).isEqualTo(2));   // ... OrderCreated + ReserveInventory are waiting
        try (Consumer<String, String> consumer = newConsumer(Topics.INVENTORY_COMMANDS)) {
            assertThatThrownBy(() -> awaitRecord(consumer, r -> isEvent(r, EventTypes.RESERVE_INVENTORY, id), Duration.ofSeconds(2)))
                    .as("nothing is published while the relay is down").isNotNull();
        }

        resumeRelay();                                   // "restart": the backlog is relayed, nothing was lost

        ConsumerRecord<String, String> reserve = awaitEvent(Topics.INVENTORY_COMMANDS, EventTypes.RESERVE_INVENTORY, id);
        awaitEvent(Topics.ORDER_EVENTS, EventTypes.ORDER_CREATED, id);
        assertThat(reserve.key()).isEqualTo(id.toString());
        Awaits.untilAsserted(() -> assertThat(pendingFor(id)).isZero());
        // and the saga carries on exactly as if nothing happened
        inventoryReplies(TestEvents.reserved(id, UUID.randomUUID(), quantityItems(id)));
        awaitStatus(id, OrderStatus.CONFIRMED);
    }

    @Test
    void appendingToTheOutboxWithoutATransactionIsRefused() {
        assertThatThrownBy(() -> outboxService.append(com.enterprise.oms.common.events.EventEnvelope.forOrder(EventTypes.ORDER_CREATED,
                UUID.randomUUID(), "order-service", "c", null, java.util.Map.of())))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    // ---- saga recovery -----------------------------------------------------------------------------------------------

    @Test
    void theRecoveryJobRedrivesASagaWhoseReplyNeverCame() throws Exception {
        UUID id = placeOrder(1);
        awaitStatus(id, OrderStatus.VALIDATED);
        awaitEvent(Topics.INVENTORY_COMMANDS, EventTypes.RESERVE_INVENTORY, id);
        assertThat(eventsWithin(Topics.INVENTORY_COMMANDS, EventTypes.RESERVE_INVENTORY, id, Duration.ofSeconds(1))).hasSize(1);

        // inventory is silent; the saga is considered stuck after 0 seconds
        sagaProps.setStuckAfterSeconds(0);
        Thread.sleep(20);
        assertThat(recoveryJob.recoverStuck()).isGreaterThanOrEqualTo(1);

        Awaits.until(Duration.ofSeconds(30), () ->
                eventsWithin(Topics.INVENTORY_COMMANDS, EventTypes.RESERVE_INVENTORY, id, Duration.ofSeconds(1)).size() == 2);
        assertThat(sagas.findByOrderId(id).orElseThrow().getAttempts()).isEqualTo(1);

        // inventory finally answers: the saga completes normally (the 2nd command was idempotent at its side)
        sagaProps.setStuckAfterSeconds(originalStuckAfter);
        inventoryReplies(TestEvents.reserved(id, UUID.randomUUID(), quantityItems(id)));
        awaitStatus(id, OrderStatus.CONFIRMED);
        assertThat(sagas.findByOrderId(id).orElseThrow().getAttempts()).isZero();    // progress resets the counter
    }

    @Test
    void anInventoryOutageDuringValidationIsRiddenOutByTheRecoveryJob() throws Exception {
        inventory.setUnavailable(true);
        UUID id = placeOrder(1);

        Awaits.until(Duration.ofSeconds(30), () -> sagas.findByOrderId(id).orElseThrow().getAttempts() >= 1);
        assertThat(statusOf(id)).isEqualTo(OrderStatus.CREATED);                   // 202 was answered, saga keeps trying
        assertThat(sagas.findByOrderId(id).orElseThrow().getLastError()).contains("InventoryUnavailableException");

        inventory.setUnavailable(false);                                             // inventory is back
        sagaProps.setStuckAfterSeconds(0);
        Thread.sleep(20);
        recoveryJob.recoverStuck();

        awaitStatus(id, OrderStatus.VALIDATED);
        awaitEvent(Topics.INVENTORY_COMMANDS, EventTypes.RESERVE_INVENTORY, id);
    }

    @Test
    void anOrderThatInventoryNeverAnswersEndsFailedAfterTheMaximumAttemptsAndReleasesDefensively() throws Exception {
        UUID id = placeOrder(1);
        awaitStatus(id, OrderStatus.VALIDATED);
        int max = sagaProps.getMaxAttempts();

        for (int i = 0; i <= max; i++) {
            orchestrator.recover(id, false);
        }

        assertThat(statusOf(id)).isEqualTo(OrderStatus.FAILED);
        assertThat(sagas.findByOrderId(id).orElseThrow().getState()).isEqualTo(SagaState.FAILED);
        awaitEvent(Topics.ORDER_EVENTS, EventTypes.ORDER_FAILED, id);
        ConsumerRecord<String, String> release = awaitEvent(Topics.INVENTORY_COMMANDS, EventTypes.RELEASE_INVENTORY, id);
        assertThat(payloadOf(release).get("reason").asText()).isEqualTo("CANCELLATION");
    }

    // ---- reconciliation ----------------------------------------------------------------------------------------------

    @Test
    void reconciliationReissuesTheReleaseOfAReservationThatWasNeverConfirmedReleased() throws Exception {
        Customer customer = newCustomer();
        UUID id = placeOrder(customer, newProduct(100), 1);
        awaitStatus(id, OrderStatus.VALIDATED);
        mvc.perform(post("/api/v1/orders/{id}/cancel", id).header("Authorization", JwtTestTokens.customer(customer.getId())))
                .andExpect(status().isOk());
        UUID reservation = UUID.randomUUID();
        inventoryReplies(TestEvents.reserved(id, reservation, quantityItems(id)));    // late reservation -> release #1
        awaitEvent(Topics.INVENTORY_COMMANDS, EventTypes.RELEASE_INVENTORY, id);
        Awaits.until(Duration.ofSeconds(30), () -> sagas.findByOrderId(id).orElseThrow().getReservationId() != null);
        Thread.sleep(20);

        ReconciliationResult first = reconciliation.run("it-admin");          // inventory never confirmed: repair

        assertThat(first.details()).anyMatch(d -> d.contains(id.toString()));
        Awaits.until(Duration.ofSeconds(30), () ->
                eventsWithin(Topics.INVENTORY_COMMANDS, EventTypes.RELEASE_INVENTORY, id, Duration.ofSeconds(1)).size() == 2);

        inventoryReplies(TestEvents.released(id, reservation, "CANCELLATION"));    // now inventory confirms
        Awaits.until(Duration.ofSeconds(30), () -> sagas.findByOrderId(id).orElseThrow().isReservationReleased());
        Thread.sleep(20);
        ReconciliationResult second = reconciliation.run("it-admin");

        assertThat(second.details()).noneMatch(d -> d.contains(id.toString()));
        assertThat(statusOf(id)).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void reconciliationRestartsFulfillmentForAConfirmedOrderThatNeverProgressed() throws Exception {
        UUID[] confirmed = confirmedOrder();
        UUID id = confirmed[0];
        awaitEvent(Topics.FULFILLMENT_COMMANDS, EventTypes.START_FULFILLMENT, id);
        Thread.sleep(20);

        ReconciliationResult r = reconciliation.run("it-admin");

        assertThat(r.details()).anyMatch(d -> d.contains(id.toString()) && d.contains("StartFulfillment"));
        Awaits.until(Duration.ofSeconds(30), () ->
                eventsWithin(Topics.FULFILLMENT_COMMANDS, EventTypes.START_FULFILLMENT, id, Duration.ofSeconds(1)).size() == 2);
        fulfillmentReplies(TestEvents.fulfillmentCompleted(id, "TRK-RECON"));
        awaitStatus(id, OrderStatus.FULFILLED);
    }

    @Test
    void theAdminEndpointRunsReconciliation() throws Exception {
        mvc.perform(post("/api/v1/admin/reconciliation/run").header("Authorization", JwtTestTokens.admin()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/reconciliation/run").header("Authorization", JwtTestTokens.operator()))
                .andExpect(status().isForbidden());
    }
}

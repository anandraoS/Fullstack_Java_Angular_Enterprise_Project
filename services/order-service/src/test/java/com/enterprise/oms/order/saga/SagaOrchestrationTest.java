package com.enterprise.oms.order.saga;

import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.domain.SagaState;
import com.enterprise.oms.order.domain.SagaStep;
import com.enterprise.oms.order.domain.StepStatus;
import com.enterprise.oms.order.entity.CustomerOrder;
import com.enterprise.oms.order.entity.SagaInstance;
import com.enterprise.oms.order.entity.SagaStepLog;
import com.enterprise.oms.order.support.AbstractOrderSpringTest;
import com.enterprise.oms.order.support.StubInventoryClient;
import com.enterprise.oms.order.support.TestEvents;
import com.enterprise.oms.testsupport.JwtTestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The saga against a real database (H2 in PostgreSQL mode, real Flyway schema), driven event by event through the
 * real idempotent router. Every assertion looks at the outbox rows, i.e. at what would be sent to Kafka.
 */
class SagaOrchestrationTest extends AbstractOrderSpringTest {

    private SagaInstance saga(UUID orderId) {
        return sagas.findByOrderId(orderId).orElseThrow();
    }

    private List<SagaStepLog> steps(UUID orderId) {
        return stepLog.findByOrderIdOrderByIdAsc(orderId);
    }

    private boolean hasStep(UUID orderId, SagaStep step, StepStatus status) {
        return steps(orderId).stream().anyMatch(s -> s.getStep() == step && s.getStatus() == status);
    }

    private double counter(String name, String tagKey, String tagValue) {
        var c = meters.find(name).tag(tagKey, tagValue).counter();
        return c == null ? 0 : c.count();
    }

    // ---- forward path --------------------------------------------------------------------------------------------

    @Test
    void validationPricesTheOrderAndEmitsOrderCreatedAndReserveInventory() throws Exception {
        UUID id = validatedOrder();

        CustomerOrder o = order(id);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.VALIDATED);
        assertThat(o.getTotalAmount()).isEqualByComparingTo("20.00");
        assertThat(o.getItems().get(0).getSku()).startsWith("SKU-");
        assertThat(outboxTypes(id)).containsExactly("OrderCreated", "ReserveInventory");

        JsonNode created = outboxPayload(id, "OrderCreated");
        assertThat(created.get("orderId").asText()).isEqualTo(id.toString());
        assertThat(created.get("customerEmail").asText()).endsWith("@example.test");
        assertThat(created.get("totalAmount").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(created.get("currency").asText()).isEqualTo("USD");
        assertThat(created.get("shippingCity").asText()).isEqualTo("Berlin");
        assertThat(created.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("10.00");
        JsonNode reserve = outboxPayload(id, "ReserveInventory");
        assertThat(reserve.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(reserve.get("reservationTtlSeconds").asInt()).isEqualTo(900);

        assertThat(saga(id).getCurrentStep()).isEqualTo(SagaStep.RESERVE_INVENTORY);
        assertThat(hasStep(id, SagaStep.VALIDATE, StepStatus.SUCCEEDED)).isTrue();
        assertThat(auditActions(id)).contains("STATUS_VALIDATED");
    }

    @Test
    void outboxEnvelopesCarryCorrelationKeyAndTopicForTheContract() throws Exception {
        UUID id = validatedOrder();
        var rows = outboxFor(id);
        assertThat(rows).extracting(r -> r.getTopic()).containsExactly("oms.order.events.v1", "oms.inventory.commands.v1");
        assertThat(rows).extracting(r -> r.getEventKey()).containsOnly(id.toString());
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getAggregateType()).isEqualTo("Order");
            assertThat(r.getPayload()).contains("\"producer\":\"order-service\"").contains("\"correlationId\"");
        });
    }

    @Test
    void fullHappyPathEndsFulfilledWithTheContractEvents() throws Exception {
        UUID id = validatedOrder();
        UUID reservation = UUID.randomUUID();
        Timer fulfilled = meters.find("oms.order.processing.duration").tag("outcome", "FULFILLED").timer();
        long before = fulfilled == null ? 0 : fulfilled.count();

        deliverInventory(TestEvents.reserved(id, reservation, quantityItems(id)));
        assertThat(statusOf(id)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(outboxTypes(id)).containsSubsequence("OrderCreated", "ReserveInventory", "OrderConfirmed", "StartFulfillment");
        JsonNode start = outboxPayload(id, "StartFulfillment");
        assertThat(start.get("shippingAddress").get("city").asText()).isEqualTo("Berlin");
        assertThat(start.get("items").get(0).get("sku").asText()).startsWith("SKU-");
        assertThat(outboxPayload(id, "OrderConfirmed").get("totalAmount").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(saga(id).getReservationId()).isEqualTo(reservation);
        assertThat(saga(id).getCurrentStep()).isEqualTo(SagaStep.FULFILL);

        deliverFulfillment(TestEvents.fulfillmentStarted(id));
        assertThat(saga(id).isFulfillmentStarted()).isTrue();
        deliverFulfillment(TestEvents.fulfillmentCompleted(id, "TRK-123"));

        assertThat(statusOf(id)).isEqualTo(OrderStatus.FULFILLED);
        assertThat(saga(id).getState()).isEqualTo(SagaState.COMPLETED);
        assertThat(outboxPayload(id, "OrderFulfilled").get("trackingNumber").asText()).isEqualTo("TRK-123");
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/orders/{id}/history", id).header("Authorization", JwtTestTokens.admin()))
                .andExpect(status().isOk());
        assertThat(auditActions(id)).contains("STATUS_VALIDATED", "STATUS_INVENTORY_RESERVED", "STATUS_CONFIRMED",
                "STATUS_FULFILLED");
        Timer after = meters.find("oms.order.processing.duration").tag("outcome", "FULFILLED").timer();
        assertThat(after.count()).isEqualTo(before + 1);
    }

    @Test
    void statusHistoryRecordsEveryStepIncludingTheTransientReservedState() throws Exception {
        UUID id = confirmedOrder();
        String body = mvc.perform(MockMvcRequestBuilders.get("/api/v1/orders/{id}/history", id)
                .header("Authorization", JwtTestTokens.operator())).andReturn().getResponse().getContentAsString();
        JsonNode rows = mapper.readTree(body);
        List<String> chain = new java.util.ArrayList<>();
        rows.forEach(r -> chain.add(r.get("toStatus").asText()));
        assertThat(chain).containsExactly("CREATED", "VALIDATED", "INVENTORY_RESERVED", "CONFIRMED");
    }

    // ---- rejection paths -----------------------------------------------------------------------------------------

    @Test
    void unknownInactiveAndOutOfStockProductsRejectTheOrderAtValidation() throws Exception {
        var customer = newCustomer();
        StubInventoryClient.Product inactive = inventory.addInactiveProduct("SKU-OFF-" + UUID.randomUUID().toString().substring(0, 5), "1.00");
        StubInventoryClient.Product scarce = inventory.addProduct("SKU-LOW-" + UUID.randomUUID().toString().substring(0, 5), "1.00", 3);
        for (UUID productId : List.of(UUID.randomUUID(), inactive.id(), scarce.id())) {
            var token = JwtTestTokens.customer(customer.getId());
            int quantity = productId.equals(scarce.id()) ? 5 : 1;
            var r = mvc.perform(createOrder(token, newKey(), orderJson(customer.getId(), productId, quantity))).andReturn();
            UUID id = UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).get("id").asText());

            validate(id);

            assertThat(statusOf(id)).isEqualTo(OrderStatus.REJECTED);
            assertThat(order(id).getStatusReason()).startsWith("Validation failed:");
            assertThat(outboxTypes(id)).containsExactly("OrderRejected");
            assertThat(saga(id).getState()).isEqualTo(SagaState.ABORTED);
        }
    }

    @Test
    void rejectionReasonNamesTheProblem() throws Exception {
        var customer = newCustomer();
        StubInventoryClient.Product scarce = inventory.addProduct("SKU-LOW2-" + UUID.randomUUID().toString().substring(0, 5), "1.00", 3);
        UUID id = placeOrder(customer, scarce, 9);
        validate(id);
        assertThat(order(id).getStatusReason()).contains(scarce.sku()).contains("INSUFFICIENT_STOCK");
    }

    @Test
    void reservationFailureRejectsTheOrderWithTheShortage() throws Exception {
        UUID id = validatedOrder();
        UUID productId = order(id).getItems().get(0).getProductId();

        deliverInventory(TestEvents.reservationFailed(id, productId, 2, 1));

        assertThat(statusOf(id)).isEqualTo(OrderStatus.REJECTED);
        assertThat(order(id).getStatusReason()).contains("Insufficient stock").contains("requested 2 available 1");
        assertThat(outboxTypes(id)).containsSubsequence("ReserveInventory", "OrderRejected");
        assertThat(outboxPayload(id, "OrderRejected").get("reason").asText()).contains("Insufficient stock");
        assertThat(saga(id).getState()).isEqualTo(SagaState.ABORTED);
        assertThat(hasStep(id, SagaStep.RESERVE_INVENTORY, StepStatus.FAILED)).isTrue();
    }

    // ---- cancellation and compensation ----------------------------------------------------------------------------

    private void cancel(UUID id) throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/orders/{id}/cancel", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"test cancel\"}").header("Authorization", JwtTestTokens.admin())).andExpect(status().isOk());
    }

    @Test
    void cancelAfterConfirmationCompensatesByReleasingTheInventory() throws Exception {
        UUID id = confirmedOrder();
        double before = counter("oms.saga.compensations", "reason", "CANCELLATION");

        cancel(id);

        assertThat(statusOf(id)).isEqualTo(OrderStatus.COMPENSATION_PENDING);
        assertThat(saga(id).getState()).isEqualTo(SagaState.COMPENSATING);
        assertThat(outboxPayload(id, "ReleaseInventory").get("reason").asText()).isEqualTo("CANCELLATION");
        assertThat(outboxTypes(id)).doesNotContain("OrderCancelled");   // only after the release is confirmed
        assertThat(counter("oms.saga.compensations", "reason", "CANCELLATION")).isEqualTo(before + 1);

        deliverInventory(TestEvents.released(id, saga(id).getReservationId(), "CANCELLATION"));

        assertThat(statusOf(id)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order(id).getStatusReason()).isEqualTo("test cancel");
        assertThat(saga(id).getState()).isEqualTo(SagaState.COMPENSATED);
        assertThat(saga(id).isReservationReleased()).isTrue();
        assertThat(outboxPayload(id, "OrderCancelled").get("reason").asText()).isEqualTo("test cancel");
    }

    @Test
    void lateReservationAfterCancelIsReleasedAndTheOrderStaysCancelled() throws Exception {
        UUID id = validatedOrder();
        cancel(id);
        assertThat(statusOf(id)).isEqualTo(OrderStatus.CANCELLED);
        double before = counter("oms.saga.compensations", "reason", "LATE_RESERVATION");
        UUID reservation = UUID.randomUUID();

        deliverInventory(TestEvents.reserved(id, reservation, quantityItems(id)));

        assertThat(statusOf(id)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(outboxCount(id, "ReleaseInventory")).isEqualTo(1);
        assertThat(outboxCount(id, "OrderConfirmed")).isZero();
        assertThat(outboxCount(id, "StartFulfillment")).isZero();
        assertThat(saga(id).getReservationId()).isEqualTo(reservation);
        assertThat(saga(id).isReservationReleased()).isFalse();
        assertThat(counter("oms.saga.compensations", "reason", "LATE_RESERVATION")).isEqualTo(before + 1);
        assertThat(auditActions(id)).contains("LATE_RESERVATION_RELEASED");

        deliverInventory(TestEvents.released(id, reservation, "CANCELLATION"));
        assertThat(saga(id).isReservationReleased()).isTrue();
        assertThat(statusOf(id)).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void fulfillmentFailureCompensatesAndEndsFailed() throws Exception {
        UUID id = confirmedOrder();

        deliverFulfillment(TestEvents.fulfillmentFailed(id, "address undeliverable"));

        assertThat(statusOf(id)).isEqualTo(OrderStatus.COMPENSATION_PENDING);
        assertThat(order(id).getStatusReason()).contains("address undeliverable");
        assertThat(outboxPayload(id, "ReleaseInventory").get("reason").asText()).isEqualTo("FULFILLMENT_FAILURE");
        assertThat(saga(id).getReason()).isEqualTo("FULFILLMENT_FAILURE");

        deliverInventory(TestEvents.released(id, saga(id).getReservationId(), "FULFILLMENT_FAILURE"));

        assertThat(statusOf(id)).isEqualTo(OrderStatus.FAILED);
        assertThat(saga(id).getState()).isEqualTo(SagaState.FAILED);
        assertThat(outboxPayload(id, "OrderFailed").get("reason").asText()).contains("address undeliverable");
        assertThat(outboxCount(id, "OrderCancelled")).isZero();
    }

    @Test
    void reservationExpiryFailsAnOrderThatIsStillWaitingToBeConfirmed() throws Exception {
        UUID id = validatedOrder();

        deliverInventory(TestEvents.released(id, UUID.randomUUID(), "EXPIRED"));

        assertThat(statusOf(id)).isEqualTo(OrderStatus.FAILED);
        assertThat(order(id).getStatusReason()).contains("expired");
        assertThat(saga(id).getState()).isEqualTo(SagaState.FAILED);
        assertThat(saga(id).isReservationReleased()).isTrue();
        assertThat(outboxPayload(id, "OrderFailed").get("reason").asText()).contains("expired");
    }

    @Test
    void expiryAfterConfirmationIsIgnored() throws Exception {
        UUID id = confirmedOrder();
        deliverInventory(TestEvents.released(id, saga(id).getReservationId(), "EXPIRED"));
        assertThat(statusOf(id)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(outboxCount(id, "OrderFailed")).isZero();
    }

    // ---- duplicates, replays, out of order, unknown ---------------------------------------------------------------

    @Test
    void theSameEventIdDeliveredTwiceHasOneEffect() throws Exception {
        UUID id = confirmedOrder();
        EventEnvelope<?> failed = TestEvents.fulfillmentFailed(id, "boom");
        long duplicates = messagingMetrics.duplicatesTotal();

        deliverFulfillment(failed);
        deliverFulfillment(failed);

        assertThat(outboxCount(id, "ReleaseInventory")).isEqualTo(1);
        assertThat(messagingMetrics.duplicatesTotal()).isEqualTo(duplicates + 1);
    }

    @Test
    void anOldEventReplayedUnderANewIdAfterTheOrderAdvancedChangesNothing() throws Exception {
        UUID id = validatedOrder();
        EventEnvelope<?> reserved = TestEvents.reserved(id, UUID.randomUUID(), quantityItems(id));
        deliverInventory(reserved);
        deliverFulfillment(TestEvents.fulfillmentCompleted(id, "TRK-9"));
        assertThat(statusOf(id)).isEqualTo(OrderStatus.FULFILLED);
        int outboxBefore = outboxFor(id).size();
        int historyBefore = auditActions(id).size();

        deliverInventory(TestEvents.withNewId(reserved));                      // InventoryReserved again
        deliverFulfillment(TestEvents.fulfillmentCompleted(id, "TRK-9b"));      // FulfillmentCompleted again

        assertThat(statusOf(id)).isEqualTo(OrderStatus.FULFILLED);
        assertThat(outboxFor(id)).hasSize(outboxBefore);                         // no second StartFulfillment/OrderFulfilled
        assertThat(outboxPayload(id, "OrderFulfilled").get("trackingNumber").asText()).isEqualTo("TRK-9");
        assertThat(auditActions(id).size()).isGreaterThanOrEqualTo(historyBefore);
    }

    @Test
    void fulfillmentCompletedBeforeStartedAndStaleFailureAfterwardsAreTolerated() throws Exception {
        UUID id = confirmedOrder();

        deliverFulfillment(TestEvents.fulfillmentCompleted(id, "TRK-OOO"));     // completed arrives first
        deliverFulfillment(TestEvents.fulfillmentStarted(id));                 // the 'started' event is late
        deliverFulfillment(TestEvents.fulfillmentFailed(id, "stale failure"));  // and a stale failure after FULFILLED

        assertThat(statusOf(id)).isEqualTo(OrderStatus.FULFILLED);
        assertThat(outboxCount(id, "ReleaseInventory")).isZero();
        assertThat(auditActions(id)).contains("TRANSITION_IGNORED");
        assertThat(saga(id).getState()).isEqualTo(SagaState.COMPLETED);
    }

    @Test
    void releasedBeforeAnyCompensationOnlyDoesBookkeeping() throws Exception {
        UUID id = confirmedOrder();
        deliverInventory(TestEvents.released(id, UUID.randomUUID(), "CANCELLATION"));   // nobody asked for it
        assertThat(statusOf(id)).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void eventsForUnknownOrdersAreIgnoredAndCounted() {
        UUID ghost = UUID.randomUUID();
        double before = counter("oms.saga.events.ignored", "type", "InventoryReserved.unknownOrder");

        deliverInventory(TestEvents.reserved(ghost, UUID.randomUUID(), List.of(new EventPayloads.QuantityItem(UUID.randomUUID(), 1))));

        assertThat(counter("oms.saga.events.ignored", "type", "InventoryReserved.unknownOrder")).isEqualTo(before + 1);
        assertThat(orders.findById(ghost)).isEmpty();
    }

    @Test
    void unknownEventTypesAreIgnoredByTheRouter() {
        long ignored = messagingMetrics.ignoredTotal();
        routing.route("oms.inventory.events.v1", TestEvents.json(EventEnvelope.forOrder("SomethingNew", UUID.randomUUID(),
                "inventory-service", "c1", null, java.util.Map.of("x", 1))));
        assertThat(messagingMetrics.ignoredTotal()).isEqualTo(ignored + 1);
    }

    // ---- validation outage ---------------------------------------------------------------------------------------

    @Test
    void inventoryOutageKeepsTheOrderCreatedCountsAttemptsAndFailsItAfterTheLimit() throws Exception {
        UUID id = createdOrder();
        inventory.setUnavailable(true);
        try {
            for (int i = 1; i <= 4; i++) {
                validate(id);
                assertThat(statusOf(id)).isEqualTo(OrderStatus.CREATED);
                assertThat(saga(id).getAttempts()).isEqualTo(i);
            }
            assertThat(saga(id).getLastError()).contains("InventoryUnavailableException");
            assertThat(hasStep(id, SagaStep.VALIDATE, StepStatus.FAILED)).isTrue();

            validate(id);   // 5th failure = max-attempts
        } finally {
            inventory.setUnavailable(false);
        }

        assertThat(statusOf(id)).isEqualTo(OrderStatus.FAILED);
        assertThat(saga(id).getState()).isEqualTo(SagaState.FAILED);
        assertThat(order(id).getStatusReason()).contains("Validation failed after 5 attempts");
        assertThat(outboxTypes(id)).containsExactly("OrderFailed");
        assertThat(outboxCount(id, "ReleaseInventory")).isZero();     // nothing was reserved
    }

    @Test
    void validationOfACancelledOrderDoesNothing() throws Exception {
        UUID id = createdOrder();
        cancel(id);
        int calls = inventory.calls();

        validate(id);

        assertThat(inventory.calls()).isEqualTo(calls);
        assertThat(statusOf(id)).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void validatingTwiceIsHarmless() throws Exception {
        UUID id = validatedOrder();
        validate(id);
        assertThat(outboxCount(id, "ReserveInventory")).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo(OrderStatus.VALIDATED);
    }

    // ---- optimistic locking --------------------------------------------------------------------------------------

    @Test
    void anOptimisticLockConflictIsRetriedAgainstTheWinnersState() throws Exception {
        UUID id = createdOrder();
        AtomicInteger attempts = new AtomicInteger();
        var requiresNew = new org.springframework.transaction.support.TransactionTemplate(
                tx.getTransactionManager(), new org.springframework.transaction.support.DefaultTransactionDefinition(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW));

        OrderStatus seen = retryTx.run(() -> {
            CustomerOrder o = orders.findById(id).orElseThrow();
            if (attempts.getAndIncrement() == 0) {
                // a competing transaction commits a change to the same row after we read it
                requiresNew.executeWithoutResult(s -> orders.findById(id).orElseThrow()
                        .changeStatus(OrderStatus.VALIDATED, "winner", java.time.Instant.now()));
            }
            OrderStatus statusWhenDeciding = o.getStatus();
            o.changeStatus(statusWhenDeciding, "loser touched it", java.time.Instant.now());
            return statusWhenDeciding;
        });

        assertThat(attempts.get()).isEqualTo(2);                                  // first attempt lost, second won
        assertThat(seen).isEqualTo(OrderStatus.VALIDATED);                        // and it saw the winner's state
        assertThat(order(id).getStatusReason()).isEqualTo("loser touched it");
        assertThat(order(id).getVersion()).isGreaterThanOrEqualTo(2L);
    }

    @Test
    void cancelRacingASagaEventNeverCorruptsTheOrder() throws Exception {
        for (int round = 0; round < 3; round++) {
            UUID id = validatedOrder();
            var reserved = TestEvents.reserved(id, UUID.randomUUID(), quantityItems(id));
            var cancelThread = new Thread(() -> {
                try {
                    mvc.perform(MockMvcRequestBuilders.post("/api/v1/orders/{id}/cancel", id)
                            .header("Authorization", JwtTestTokens.admin()));
                } catch (Exception ignored) {
                    // outcome asserted below
                }
            });
            cancelThread.start();
            try {
                deliverInventory(reserved);
            } catch (RuntimeException e) {
                // a version conflict makes the Kafka container retry; emulate the retry once
                deliverInventory(reserved);
            }
            cancelThread.join();

            OrderStatus end = statusOf(id);
            assertThat(end).isIn(OrderStatus.CANCELLED, OrderStatus.COMPENSATION_PENDING);
            if (end == OrderStatus.COMPENSATION_PENDING) {
                // cancel ran after confirmation: a release was requested exactly once
                assertThat(outboxCount(id, "ReleaseInventory")).isEqualTo(1);
            } else {
                // cancel won: the late reservation must have been released instead of confirmed
                assertThat(outboxCount(id, "OrderConfirmed")).isZero();
                assertThat(outboxCount(id, "ReleaseInventory")).isEqualTo(1);
            }
        }
    }
}

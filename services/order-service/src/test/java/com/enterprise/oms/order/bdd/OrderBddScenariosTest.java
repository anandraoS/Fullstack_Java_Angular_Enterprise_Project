package com.enterprise.oms.order.bdd;

import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.entity.Customer;
import com.enterprise.oms.order.support.AbstractOrderSpringTest;
import com.enterprise.oms.order.support.StubInventoryClient;
import com.enterprise.oms.order.support.TestEvents;
import com.enterprise.oms.testsupport.JwtTestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behaviour scenarios in Given / When / Then form. Each @DisplayName is the title of the same scenario in
 * {@code tests/bdd/order-scenarios.feature} (1:1, ids ORD-A1 ... ORD-E2 are in the comments). The tests run through the
 * real HTTP layer, security, database and saga; only inventory-service and fulfillment-service are played by the
 * test (stub validation endpoint, hand-made reply events).
 */
class OrderBddScenariosTest extends AbstractOrderSpringTest {

    private Customer acme;
    private StubInventoryClient.Product widget;

    @BeforeEach
    void background() {
        // Background: Given a customer "Acme" and a product "Widget" costing 12.50 with 100 units in stock
        acme = newCustomer();
        widget = newProduct("12.50", 100);
    }

    private String acmeToken() {
        return JwtTestTokens.customer(acme.getId());
    }

    private MvcResult submit(String key, int quantity) throws Exception {
        return mvc.perform(createOrder(acmeToken(), key, orderJson(acme.getId(), widget.id(), quantity))).andReturn();
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void theCustomerCancels(UUID id, String reason) throws Exception {
        mvc.perform(post("/api/v1/orders/{id}/cancel", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}").header("Authorization", acmeToken())).andExpect(status().isOk());
    }

    // =================================================================================================================
    @Nested
    @DisplayName("Rule: Order creation")
    class OrderCreation {

        @Test
        @DisplayName("Scenario: A customer places a valid order and it is confirmed once stock is reserved")   // ORD-A1
        void validOrderIsConfirmed() throws Exception {
            // Given the customer is signed in
            // When the customer submits an order for 4 widgets
            MvcResult accepted = submit(newKey(), 4);
            UUID id = idOf(accepted);

            // Then the order is accepted for asynchronous processing
            assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
            assertThat(accepted.getResponse().getHeader("Location")).isEqualTo("/api/v1/orders/" + id);
            // And the order is CREATED and a saga has started
            assertThat(statusOf(id)).isEqualTo(OrderStatus.CREATED);
            assertThat(sagas.findByOrderId(id)).isPresent();

            // When the order is validated against inventory and inventory reserves the stock
            validate(id);
            deliverInventory(TestEvents.reserved(id, UUID.randomUUID(), quantityItems(id)));

            // Then the order is CONFIRMED and priced from the validated catalog prices
            mvc.perform(get("/api/v1/orders/{id}", id).header("Authorization", acmeToken()))
                    .andExpect(jsonPath("$.status", is("CONFIRMED")))
                    .andExpect(jsonPath("$.totalAmount", is(50.0)));
            // And OrderConfirmed and StartFulfillment are published
            assertThat(outboxTypes(id)).contains("OrderCreated", "ReserveInventory", "OrderConfirmed", "StartFulfillment");
        }

        @Test
        @DisplayName("Scenario: A customer cannot place an order for another customer")   // ORD-A2
        void cannotOrderForSomeoneElse() throws Exception {
            // Given another customer "Globex"
            Customer globex = newCustomer();

            // When Acme submits an order whose customerId is Globex
            MvcResult r = mvc.perform(createOrder(acmeToken(), newKey(), orderJson(globex.getId(), widget.id(), 1))).andReturn();

            // Then the request is refused with 403 FORBIDDEN
            assertThat(r.getResponse().getStatus()).isEqualTo(403);
            assertThat(r.getResponse().getContentAsString()).contains("\"code\":\"FORBIDDEN\"");
            // And no order exists for Globex
            assertThat(orders.count((root, q, cb) -> cb.equal(root.get("customerId"), globex.getId()))).isZero();
        }

        @Test
        @DisplayName("Scenario: An order with an invalid body is refused with field errors")   // ORD-A3
        void invalidBodyIsRefused() throws Exception {
            // When Acme submits an order with quantity 0 and no postal code
            String body = "{\"customerId\":\"" + acme.getId() + "\",\"items\":[{\"productId\":\"" + widget.id()
                    + "\",\"quantity\":0}],\"shippingAddress\":{\"line1\":\"1 A\",\"city\":\"B\",\"country\":\"DE\"}}";

            // Then the response is 400 VALIDATION_FAILED listing both fields
            mvc.perform(createOrder(acmeToken(), newKey(), body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
                    .andExpect(jsonPath("$.errors[*].field", org.hamcrest.Matchers.hasItems("items[0].quantity", "shippingAddress.postalCode")));
        }
    }

    // =================================================================================================================
    @Nested
    @DisplayName("Rule: Insufficient inventory")
    class InsufficientInventory {

        @Test
        @DisplayName("Scenario: Validation finds the product out of stock")   // ORD-B1
        void rejectedAtValidation() throws Exception {
            // Given a product with only 3 units in stock
            StubInventoryClient.Product scarce = newProduct("5.00", 3);

            // When Acme orders 10 units and the order is validated
            UUID id = placeOrder(acme, scarce, 10);
            validate(id);

            // Then the order is REJECTED with the reason naming the product and the stock problem
            assertThat(statusOf(id)).isEqualTo(OrderStatus.REJECTED);
            assertThat(order(id).getStatusReason()).contains(scarce.sku()).contains("INSUFFICIENT_STOCK");
            // And OrderRejected is published and no inventory is reserved
            assertThat(outboxTypes(id)).containsExactly("OrderRejected");
        }

        @Test
        @DisplayName("Scenario: Inventory cannot reserve the requested quantity")   // ORD-B2
        void rejectedAtReservation() throws Exception {
            // Given a validated order whose stock is taken by someone else before the reservation
            UUID id = placeOrder(acme, widget, 4);
            validate(id);
            assertThat(statusOf(id)).isEqualTo(OrderStatus.VALIDATED);

            // When inventory answers InventoryReservationFailed with a shortage
            deliverInventory(TestEvents.reservationFailed(id, widget.id(), 4, 1));

            // Then the order is REJECTED and the customer can see why
            mvc.perform(get("/api/v1/orders/{id}", id).header("Authorization", acmeToken()))
                    .andExpect(jsonPath("$.status", is("REJECTED")))
                    .andExpect(jsonPath("$.statusReason", org.hamcrest.Matchers.containsString("requested 4 available 1")));
            // And OrderRejected is published
            assertThat(outboxCount(id, "OrderRejected")).isEqualTo(1);
        }
    }

    // =================================================================================================================
    @Nested
    @DisplayName("Rule: Duplicate requests")
    class DuplicateRequests {

        @Test
        @DisplayName("Scenario: The same request is submitted twice with the same Idempotency-Key")   // ORD-C1
        void sameKeySameBody() throws Exception {
            // Given Acme already submitted an order with key K
            String key = newKey();
            MvcResult first = submit(key, 2);

            // When Acme submits the identical request with key K again (for example after a timeout)
            mvc.perform(createOrder(acmeToken(), key, orderJson(acme.getId(), widget.id(), 2)))
                    // Then the original order is returned and marked as a replay
                    .andExpect(status().isAccepted())
                    .andExpect(header().string("Idempotent-Replay", "true"))
                    .andExpect(jsonPath("$.id", is(idOf(first).toString())));
            // And exactly one order exists
            assertThat(orders.count((root, q, cb) -> cb.equal(root.get("customerId"), acme.getId()))).isEqualTo(1);
        }

        @Test
        @DisplayName("Scenario: The same Idempotency-Key is reused with a different order")   // ORD-C2
        void sameKeyDifferentBody() throws Exception {
            // Given Acme submitted an order for 2 widgets with key K
            String key = newKey();
            submit(key, 2);

            // When Acme submits an order for 5 widgets with the same key
            mvc.perform(createOrder(acmeToken(), key, orderJson(acme.getId(), widget.id(), 5)))
                    // Then it is refused with 422 IDEMPOTENCY_KEY_REUSED
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REUSED")));
            // And still only one order exists
            assertThat(orders.count((root, q, cb) -> cb.equal(root.get("customerId"), acme.getId()))).isEqualTo(1);
        }

        @Test
        @DisplayName("Scenario: Two identical requests arrive at the same moment")   // ORD-C3
        void concurrentDuplicates() throws Exception {
            // Given two clients send the same request with the same key at the same instant
            String key = newKey();
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> {
                    go.await();
                    return submit(key, 1);
                }));
            }

            // When both are processed
            go.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> f : calls) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            pool.shutdownNow();

            // Then both get 202 with the same order, and exactly one order was created
            assertThat(results).extracting(r -> r.getResponse().getStatus()).containsOnly(202);
            assertThat(results.stream().map(r -> r.getResponse().getHeader("Location")).distinct()).hasSize(1);
            assertThat(orders.count((root, q, cb) -> cb.equal(root.get("customerId"), acme.getId()))).isEqualTo(1);
        }

        @Test
        @DisplayName("Scenario: Inventory's reply is delivered twice")   // ORD-C4
        void duplicateEventDelivery() throws Exception {
            // Given a validated order
            UUID id = placeOrder(acme, widget, 1);
            validate(id);
            var reserved = TestEvents.reserved(id, UUID.randomUUID(), quantityItems(id));

            // When the same InventoryReserved event (same eventId) is delivered twice
            deliverInventory(reserved);
            deliverInventory(reserved);

            // Then the order is confirmed once: one OrderConfirmed and one StartFulfillment
            assertThat(statusOf(id)).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(outboxCount(id, "OrderConfirmed")).isEqualTo(1);
            assertThat(outboxCount(id, "StartFulfillment")).isEqualTo(1);
        }
    }

    // =================================================================================================================
    @Nested
    @DisplayName("Rule: Cancellation")
    class Cancellation {

        @Test
        @DisplayName("Scenario: A customer cancels before the order was validated")   // ORD-D1
        void cancelBeforeValidation() throws Exception {
            // Given a freshly created order
            UUID id = placeOrder(acme, widget, 1);

            // When the customer cancels it
            theCustomerCancels(id, "ordered by mistake");

            // Then it is CANCELLED immediately and OrderCancelled is published
            assertThat(statusOf(id)).isEqualTo(OrderStatus.CANCELLED);
            assertThat(outboxTypes(id)).containsExactly("OrderCancelled");
            // And a later validation does nothing
            int calls = inventory.calls();
            validate(id);
            assertThat(inventory.calls()).isEqualTo(calls);
        }

        @Test
        @DisplayName("Scenario: A customer cancels a confirmed order and the stock is released")   // ORD-D2
        void cancelConfirmedOrder() throws Exception {
            // Given a confirmed order
            UUID id = confirmedOrder();
            UUID reservation = sagas.findByOrderId(id).orElseThrow().getReservationId();

            // When the owner cancels it
            Customer owner = customers.findById(order(id).getCustomerId()).orElseThrow();
            mvc.perform(post("/api/v1/orders/{id}/cancel", id).header("Authorization", JwtTestTokens.customer(owner.getId())))
                    .andExpect(status().isOk())
                    // Then it waits in COMPENSATION_PENDING while a ReleaseInventory command goes out
                    .andExpect(jsonPath("$.status", is("COMPENSATION_PENDING")));
            assertThat(outboxCount(id, "ReleaseInventory")).isEqualTo(1);

            // When inventory confirms the release
            deliverInventory(TestEvents.released(id, reservation, "CANCELLATION"));

            // Then the order is CANCELLED and OrderCancelled is published
            assertThat(statusOf(id)).isEqualTo(OrderStatus.CANCELLED);
            assertThat(outboxCount(id, "OrderCancelled")).isEqualTo(1);
        }

        @Test
        @DisplayName("Scenario: A customer cannot cancel a fulfilled order")   // ORD-D3
        void cannotCancelFulfilled() throws Exception {
            // Given a fulfilled order
            UUID id = confirmedOrder();
            deliverFulfillment(TestEvents.fulfillmentCompleted(id, "TRK-1"));
            Customer owner = customers.findById(order(id).getCustomerId()).orElseThrow();

            // When the owner tries to cancel it
            mvc.perform(post("/api/v1/orders/{id}/cancel", id).header("Authorization", JwtTestTokens.customer(owner.getId())))
                    // Then the answer is 409 INVALID_STATE_TRANSITION and the order is unchanged
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
            assertThat(statusOf(id)).isEqualTo(OrderStatus.FULFILLED);
        }

        @Test
        @DisplayName("Scenario: A reservation arrives after the order was cancelled")   // ORD-D4
        void lateReservation() throws Exception {
            // Given an order cancelled while its reservation was still in flight
            UUID id = placeOrder(acme, widget, 1);
            validate(id);
            theCustomerCancels(id, "too slow");
            assertThat(statusOf(id)).isEqualTo(OrderStatus.CANCELLED);

            // When the InventoryReserved event arrives late
            deliverInventory(TestEvents.reserved(id, UUID.randomUUID(), quantityItems(id)));

            // Then the order stays CANCELLED and the reservation is released (compensation)
            assertThat(statusOf(id)).isEqualTo(OrderStatus.CANCELLED);
            assertThat(outboxCount(id, "ReleaseInventory")).isEqualTo(1);
            assertThat(outboxCount(id, "OrderConfirmed")).isZero();
        }
    }

    // =================================================================================================================
    @Nested
    @DisplayName("Rule: Failed fulfillment")
    class FailedFulfillment {

        @Test
        @DisplayName("Scenario: Fulfillment fails and the reserved stock is given back")   // ORD-E1
        void fulfillmentFails() throws Exception {
            // Given a confirmed order
            UUID id = confirmedOrder();
            UUID reservation = sagas.findByOrderId(id).orElseThrow().getReservationId();

            // When fulfillment reports a failure after its own retries
            deliverFulfillment(TestEvents.fulfillmentFailed(id, "address cannot be delivered"));

            // Then the order enters COMPENSATION_PENDING and ReleaseInventory(FULFILLMENT_FAILURE) is sent
            assertThat(statusOf(id)).isEqualTo(OrderStatus.COMPENSATION_PENDING);
            assertThat(outboxPayload(id, "ReleaseInventory").get("reason").asText()).isEqualTo("FULFILLMENT_FAILURE");

            // When inventory confirms the release
            deliverInventory(TestEvents.released(id, reservation, "FULFILLMENT_FAILURE"));

            // Then the order is FAILED with the reason, OrderFailed is published, and it shows up in the failed list
            assertThat(statusOf(id)).isEqualTo(OrderStatus.FAILED);
            assertThat(outboxPayload(id, "OrderFailed").get("reason").asText()).contains("address cannot be delivered");
            mvc.perform(get("/api/v1/admin/orders/failed").param("size", "100").header("Authorization", JwtTestTokens.operator()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[*].id", org.hamcrest.Matchers.hasItem(id.toString())));
        }

        @Test
        @DisplayName("Scenario: A stuck compensation is retried by an operator")   // ORD-E2
        void operatorRetriesStuckCompensation() throws Exception {
            // Given an order in COMPENSATION_PENDING whose release command got lost
            UUID id = confirmedOrder();
            deliverFulfillment(TestEvents.fulfillmentFailed(id, "courier strike"));
            assertThat(outboxCount(id, "ReleaseInventory")).isEqualTo(1);

            // When an operator retries the order
            mvc.perform(post("/api/v1/admin/orders/{id}/retry", id).header("Authorization", JwtTestTokens.operator()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("COMPENSATION_PENDING")));

            // Then the idempotent ReleaseInventory command is sent again, the saga log shows the retry
            assertThat(outboxCount(id, "ReleaseInventory")).isEqualTo(2);
            mvc.perform(get("/api/v1/admin/saga/{id}", id).header("Authorization", JwtTestTokens.operator()))
                    .andExpect(jsonPath("$.steps[?(@.status=='RETRIED')].step", org.hamcrest.Matchers.hasItem("RELEASE_INVENTORY")));

            // And when inventory finally answers, the order reaches FAILED
            deliverInventory(TestEvents.released(id, sagas.findByOrderId(id).orElseThrow().getReservationId(), "FULFILLMENT_FAILURE"));
            assertThat(statusOf(id)).isEqualTo(OrderStatus.FAILED);
        }
    }
}

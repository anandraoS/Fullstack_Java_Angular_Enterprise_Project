package com.enterprise.oms.inventory;

import com.enterprise.oms.common.error.ConflictException;
import com.enterprise.oms.common.events.EventPayloads.QuantityItem;
import com.enterprise.oms.common.events.EventPayloads.ReleaseReasons;
import com.enterprise.oms.common.events.EventPayloads.Shortage;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.inventory.domain.ReservationStatus;
import com.enterprise.oms.inventory.reservation.ReservationOutcome;
import com.enterprise.oms.inventory.service.ReservationLifecycleService;
import com.enterprise.oms.inventory.service.ReservationService;
import com.enterprise.oms.inventory.service.StockService;
import com.enterprise.oms.testsupport.JwtTestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * BDD mirror of {@code tests/bdd/inventory-scenarios.feature}: one {@code @Nested} class per scenario;
 * {@code given()} and {@code when()} run before every {@code then_*} assertion.
 */
class InventoryBddScenariosTest extends AbstractInventoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired ReservationService reservations;
    @Autowired ReservationLifecycleService lifecycle;
    @Autowired StockService stockService;

    private ReservationOutcome reserve(UUID order, QuantityItem... items) {
        return reservations.reserve(order, List.of(items), null, "bdd", null);
    }

    @Nested
    @DisplayName("Scenario: Reserve available stock")
    class ReserveAvailableStock {
        UUID product;
        UUID order = UUID.randomUUID();
        ReservationOutcome outcome;

        void given() { product = newProduct(10); }
        void when() { outcome = reserve(order, item(product, 4)); }

        @BeforeEach void setUp() { given(); when(); }

        @Test void then_the_reservation_is_RESERVED_with_an_expiry() {
            assertThat(outcome.status()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(outcome.expiresAt()).isAfter(Instant.now());
        }
        @Test void then_4_units_are_reserved_and_6_are_available() {
            assertThat(reserved(product)).isEqualTo(4);
            assertThat(onHand(product) - reserved(product)).isEqualTo(6);
        }
        @Test void then_an_InventoryReserved_event_is_queued_in_the_outbox() {
            assertThat(outboxCount(order, EventTypes.INVENTORY_RESERVED)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Scenario: Refuse a reservation when stock is insufficient")
    class RefuseWhenInsufficient {
        UUID product;
        UUID order = UUID.randomUUID();
        ReservationOutcome outcome;

        void given() { product = newProduct(3); }
        void when() { outcome = reserve(order, item(product, 5)); }

        @BeforeEach void setUp() { given(); when(); }

        @Test void then_the_reservation_is_FAILED_with_reason_INSUFFICIENT_STOCK() {
            assertThat(outcome.status()).isEqualTo(ReservationStatus.FAILED);
            assertThat(outcome.reason()).isEqualTo("INSUFFICIENT_STOCK");
        }
        @Test void and_the_shortage_reports_requested_5_and_available_3() {
            assertThat(outcome.shortages()).containsExactly(new Shortage(product, 5, 3));
        }
        @Test void and_no_stock_is_reserved() { assertThat(reserved(product)).isZero(); }
        @Test void and_an_InventoryReservationFailed_event_is_queued_in_the_outbox() {
            assertThat(outboxCount(order, EventTypes.INVENTORY_RESERVATION_FAILED)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Scenario: Reservations are all-or-nothing across order lines")
    class AllOrNothing {
        UUID productA;
        UUID productB;
        ReservationOutcome outcome;

        void given() { productA = newProduct(10); productB = newProduct(1); }
        void when() { outcome = reserve(UUID.randomUUID(), item(productA, 5), item(productB, 2)); }

        @BeforeEach void setUp() { given(); when(); }

        @Test void then_the_reservation_is_FAILED_and_only_B_is_reported_short() {
            assertThat(outcome.status()).isEqualTo(ReservationStatus.FAILED);
            assertThat(outcome.shortages()).extracting(Shortage::productId).containsExactly(productB);
        }
        @Test void and_product_A_has_nothing_reserved() { assertThat(reserved(productA)).isZero(); }
    }

    @Nested
    @DisplayName("Scenario: A duplicate reserve request returns the original outcome")
    class DuplicateRequest {
        UUID product;
        UUID order = UUID.randomUUID();
        ReservationOutcome original;
        ReservationOutcome again;

        void given() { product = newProduct(10); original = reserve(order, item(product, 4)); }
        void when() { again = reserve(order, item(product, 4)); }

        @BeforeEach void setUp() { given(); when(); }

        @Test void then_the_original_reservation_id_is_returned_flagged_as_duplicate() {
            assertThat(again.reservationId()).isEqualTo(original.reservationId());
            assertThat(again.duplicate()).isTrue();
        }
        @Test void and_still_only_4_units_are_reserved() { assertThat(reserved(product)).isEqualTo(4); }
        @Test void and_exactly_one_reservation_exists_for_the_order() { assertThat(reservationCount(order)).isEqualTo(1); }
    }

    @Nested
    @DisplayName("Scenario: The last unit goes to exactly one of two competing orders")
    class LastUnit {
        UUID product;
        final List<ReservationOutcome> outcomes = new ArrayList<>();

        void given() { product = newProduct(1); }

        void when() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch go = new CountDownLatch(1);
                List<Future<ReservationOutcome>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    UUID order = UUID.randomUUID();
                    futures.add(pool.submit(() -> {
                        go.await();
                        return reserve(order, item(product, 1));
                    }));
                }
                go.countDown();
                for (Future<ReservationOutcome> f : futures) {
                    outcomes.add(f.get(30, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }
        }

        @BeforeEach void setUp() throws Exception { given(); when(); }

        @Test void then_exactly_one_reservation_is_RESERVED_and_the_other_is_FAILED() {
            assertThat(outcomes).extracting(ReservationOutcome::status)
                    .containsExactlyInAnyOrder(ReservationStatus.RESERVED, ReservationStatus.FAILED);
        }
        @Test void and_1_unit_is_reserved_and_never_more() { assertThat(reserved(product)).isEqualTo(1); }
    }

    @Nested
    @DisplayName("Scenario: A confirmed order keeps its reservation")
    class ConfirmedOrder {
        UUID product;
        UUID order = UUID.randomUUID();

        void given() { product = newProduct(10); reserve(order, item(product, 3)); }
        void when() { lifecycle.commit(order); }

        @BeforeEach void setUp() { given(); when(); }

        @Test void then_the_reservation_is_COMMITTED_and_no_longer_expires() {
            assertThat(reservationStatus(order)).isEqualTo("COMMITTED");
            assertThat(jdbc.queryForObject("SELECT expires_at IS NULL FROM inventory_reservations WHERE order_id = ?",
                    Boolean.class, order)).isTrue();
        }
        @Test void and_the_3_units_stay_reserved() { assertThat(reserved(product)).isEqualTo(3); }
    }

    @Nested
    @DisplayName("Scenario: A cancelled order gives its stock back")
    class CancelledOrder {
        UUID product;
        UUID order = UUID.randomUUID();

        void given() { product = newProduct(10); reserve(order, item(product, 3)); }
        void when() { lifecycle.release(order, ReleaseReasons.CANCELLATION, true, "bdd", null); }

        @BeforeEach void setUp() { given(); when(); }

        @Test void then_the_reservation_is_RELEASED_with_reason_CANCELLATION() {
            assertThat(reservationStatus(order)).isEqualTo("RELEASED");
            assertThat(jdbc.queryForObject("SELECT release_reason FROM inventory_reservations WHERE order_id = ?",
                    String.class, order)).isEqualTo("CANCELLATION");
        }
        @Test void and_no_units_remain_reserved() { assertThat(reserved(product)).isZero(); }
        @Test void and_an_InventoryReleased_event_is_queued_in_the_outbox() {
            assertThat(outboxCount(order, EventTypes.INVENTORY_RELEASED)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Scenario: An unconfirmed reservation expires")
    class ExpiredReservation {
        UUID product;
        UUID order = UUID.randomUUID();

        void given() {
            product = newProduct(10);
            reserve(order, item(product, 3));
            jdbc.update("UPDATE inventory_reservations SET expires_at = ? WHERE order_id = ?",
                    Timestamp.from(Instant.now().minusSeconds(10)), order);
        }
        void when() { lifecycle.expireDue(); }

        @BeforeEach void setUp() { given(); when(); }

        @Test void then_the_reservation_is_EXPIRED_and_the_stock_is_available_again() {
            assertThat(reservationStatus(order)).isEqualTo("EXPIRED");
            assertThat(onHand(product) - reserved(product)).isEqualTo(10);
        }
        @Test void and_an_InventoryReleased_event_with_reason_EXPIRED_is_queued_in_the_outbox() {
            assertThat(outboxPayloads(order, EventTypes.INVENTORY_RELEASED)).singleElement().asString().contains("EXPIRED");
        }
    }

    @Nested
    @DisplayName("Scenario: A fulfilled order ships its stock")
    class FulfilledOrder {
        UUID product;
        UUID order = UUID.randomUUID();

        void given() { product = newProduct(10); reserve(order, item(product, 3)); lifecycle.commit(order); }
        void when() { lifecycle.ship(order); }

        @BeforeEach void setUp() { given(); when(); }

        @Test void then_on_hand_drops_to_7_and_nothing_stays_reserved() {
            assertThat(onHand(product)).isEqualTo(7);
            assertThat(reserved(product)).isZero();
        }
        @Test void and_completing_fulfillment_again_changes_nothing() {
            assertThat(lifecycle.ship(order)).isEqualTo(ReservationLifecycleService.Result.NO_OP);
            assertThat(onHand(product)).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("Scenario: Stock cannot be adjusted below what is reserved")
    class AdjustBelowReserved {
        UUID product;

        void given() { product = newProduct(10); reserve(UUID.randomUUID(), item(product, 8)); }
        void when() { /* the attempts are part of the assertions: each one is a separate behaviour */ }

        @BeforeEach void setUp() { given(); when(); }

        @Test void then_removing_3_units_is_rejected_as_a_conflict_and_on_hand_stays_10() {
            assertThatThrownBy(() -> stockService.adjust(product, -3, "damaged goods")).isInstanceOf(ConflictException.class);
            assertThat(onHand(product)).isEqualTo(10);
        }
        @Test void and_removing_2_units_is_accepted() {
            stockService.adjust(product, -2, "damaged goods");
            assertThat(onHand(product)).isEqualTo(8);
        }
    }

    @Nested
    @DisplayName("Scenario: Customers see availability but not stock internals")
    class CustomerVisibility {
        UUID product;
        String customerBody;
        String operatorBody;

        void given() { product = newProduct(10); reserve(UUID.randomUUID(), item(product, 4)); }

        void when() throws Exception {
            customerBody = mvc.perform(get("/api/v1/products/" + product)
                    .header("Authorization", JwtTestTokens.customer(UUID.fromString("c0000000-0000-0000-0000-000000000001"))))
                    .andReturn().getResponse().getContentAsString();
            operatorBody = mvc.perform(get("/api/v1/products/" + product).header("Authorization", JwtTestTokens.operator()))
                    .andReturn().getResponse().getContentAsString();
        }

        @BeforeEach void setUp() throws Exception { given(); when(); }

        @Test void then_the_response_shows_6_available() throws Exception {
            assertThat(JSON.readTree(customerBody).get("available").asInt()).isEqualTo(6);
        }
        @Test void and_it_does_not_contain_on_hand_reserved_or_version() throws Exception {
            JsonNode n = JSON.readTree(customerBody);
            assertThat(n.has("onHand")).isFalse();
            assertThat(n.has("reserved")).isFalse();
            assertThat(n.has("version")).isFalse();
        }
        @Test void and_an_operator_reading_the_same_product_sees_on_hand_10_and_reserved_4() throws Exception {
            JsonNode n = JSON.readTree(operatorBody);
            assertThat(n.get("onHand").asInt()).isEqualTo(10);
            assertThat(n.get("reserved").asInt()).isEqualTo(4);
        }
    }
}

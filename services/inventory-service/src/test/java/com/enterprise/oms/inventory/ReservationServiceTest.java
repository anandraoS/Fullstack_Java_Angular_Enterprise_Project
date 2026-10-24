package com.enterprise.oms.inventory;

import com.enterprise.oms.common.events.EventPayloads.QuantityItem;
import com.enterprise.oms.common.events.EventPayloads.Shortage;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.inventory.domain.ReservationStatus;
import com.enterprise.oms.inventory.reservation.ReservationOutcome;
import com.enterprise.oms.inventory.reservation.ReservationStrategies;
import com.enterprise.oms.inventory.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TDD: written BEFORE the implementation (see docs/tdd/inventory-reservation-tdd.md). Every behaviour is asserted
 * against all three strategies because the strategy is an implementation detail of the same contract.
 */
class ReservationServiceTest extends AbstractInventoryTest {

    @Autowired ReservationService reservations;
    @Autowired ReservationStrategies strategies;

    private ReservationOutcome reserve(UUID orderId, QuantityItem... items) {
        return reservations.reserve(orderId, List.of(items), null, "corr-test", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"atomic", "pessimistic", "optimistic"})
    void reservesAvailableStockAndEmitsInventoryReserved(String strategy) {
        strategies.select(strategy);
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        long versionBefore = stockVersion(p);

        ReservationOutcome out = reserve(order, item(p, 4));

        assertThat(out.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(out.duplicate()).isFalse();
        assertThat(out.reservationId()).isNotNull();
        assertThat(out.expiresAt()).isBetween(Instant.now().plus(Duration.ofSeconds(890)),
                Instant.now().plus(Duration.ofSeconds(910)));
        assertThat(reserved(p)).isEqualTo(4);
        assertThat(onHand(p)).isEqualTo(10);
        assertThat(stockVersion(p)).isGreaterThan(versionBefore);
        assertThat(reservationStatus(order)).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM inventory_reservation_items WHERE reservation_id = ?",
                Integer.class, out.reservationId())).isEqualTo(4);
        assertThat(outboxCount(order, EventTypes.INVENTORY_RESERVED)).isEqualTo(1);
        assertThat(outboxPayloads(order, EventTypes.INVENTORY_RESERVED).get(0))
                .contains(order.toString()).contains(out.reservationId().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"atomic", "pessimistic", "optimistic"})
    void refusesWhenStockIsInsufficientAndReportsTheShortage(String strategy) {
        strategies.select(strategy);
        UUID p = newProduct(3);
        UUID order = UUID.randomUUID();

        ReservationOutcome out = reserve(order, item(p, 5));

        assertThat(out.status()).isEqualTo(ReservationStatus.FAILED);
        assertThat(out.reason()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(out.shortages()).containsExactly(new Shortage(p, 5, 3));
        assertThat(reserved(p)).isZero();
        assertThat(reservationStatus(order)).isEqualTo("FAILED");
        assertThat(outboxCount(order, EventTypes.INVENTORY_RESERVATION_FAILED)).isEqualTo(1);
        assertThat(outboxCount(order, EventTypes.INVENTORY_RESERVED)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"atomic", "pessimistic", "optimistic"})
    void isAllOrNothingAcrossLines(String strategy) {
        strategies.select(strategy);
        UUID a = newProduct(10);
        UUID b = newProduct(1);
        UUID order = UUID.randomUUID();

        ReservationOutcome out = reserve(order, item(a, 5), item(b, 2));

        assertThat(out.status()).isEqualTo(ReservationStatus.FAILED);
        assertThat(out.shortages()).extracting(Shortage::productId).containsExactly(b);
        assertThat(reserved(a)).as("line A must not stay reserved when line B is short").isZero();
        assertThat(reserved(b)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"atomic", "pessimistic", "optimistic"})
    void duplicateOrderReturnsTheOriginalOutcomeWithoutReservingTwice(String strategy) {
        strategies.select(strategy);
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();

        ReservationOutcome first = reserve(order, item(p, 4));
        ReservationOutcome second = reserve(order, item(p, 4));

        assertThat(second.duplicate()).isTrue();
        assertThat(second.reservationId()).isEqualTo(first.reservationId());
        assertThat(second.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(reserved(p)).isEqualTo(4);
        assertThat(reservationCount(order)).isEqualTo(1);
    }

    @Test
    void duplicateOfAFailedReservationStaysFailedEvenIfStockAppearsLater() {
        UUID p = newProduct(1);
        UUID order = UUID.randomUUID();
        ReservationOutcome first = reserve(order, item(p, 5));
        jdbc.update("UPDATE stock SET on_hand = 100 WHERE product_id = ?", p);

        ReservationOutcome second = reserve(order, item(p, 5));

        assertThat(first.status()).isEqualTo(ReservationStatus.FAILED);
        assertThat(second.status()).isEqualTo(ReservationStatus.FAILED);
        assertThat(second.duplicate()).isTrue();
        assertThat(second.reservationId()).isEqualTo(first.reservationId());
        assertThat(second.shortages()).containsExactly(new Shortage(p, 5, 1));
        assertThat(reserved(p)).isZero();
    }

    @Test
    void unknownProductIsRefusedWithoutTouchingStock() {
        UUID known = newProduct(10);
        UUID unknown = UUID.randomUUID();
        UUID order = UUID.randomUUID();

        ReservationOutcome out = reserve(order, item(known, 1), item(unknown, 1));

        assertThat(out.status()).isEqualTo(ReservationStatus.FAILED);
        assertThat(out.reason()).isEqualTo("UNKNOWN_PRODUCT");
        assertThat(out.shortages()).extracting(Shortage::productId).containsExactly(unknown);
        assertThat(reserved(known)).isZero();
    }

    @Test
    void inactiveProductIsRefused() {
        UUID p = newProduct(10, false);
        ReservationOutcome out = reserve(UUID.randomUUID(), item(p, 1));
        assertThat(out.status()).isEqualTo(ReservationStatus.FAILED);
        assertThat(out.reason()).isEqualTo("PRODUCT_INACTIVE");
        assertThat(reserved(p)).isZero();
    }

    @Test
    void duplicateLinesOfTheSameProductAreMerged() {
        UUID p = newProduct(10);
        UUID order = UUID.randomUUID();
        ReservationOutcome out = reserve(order, item(p, 2), item(p, 3));
        assertThat(out.items()).containsExactly(item(p, 5));
        assertThat(reserved(p)).isEqualTo(5);
    }

    @Test
    void invalidRequestsAreRejectedAsIllegalArguments() {
        UUID p = newProduct(10);
        assertThatThrownBy(() -> reserve(UUID.randomUUID(), item(p, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reserve(UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reservations.reserve(null, List.of(item(p, 1)), null, "c", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(reserved(p)).isZero();
    }

    @Test
    void ttlDefaultsToConfiguredValueAndIsCapped() {
        UUID p = newProduct(10);
        ReservationOutcome withDefault = reservations.reserve(UUID.randomUUID(), List.of(item(p, 1)), null, "c", null);
        ReservationOutcome capped = reservations.reserve(UUID.randomUUID(), List.of(item(p, 1)), 99_999_999, "c", null);
        ReservationOutcome short_ = reservations.reserve(UUID.randomUUID(), List.of(item(p, 1)), 60, "c", null);
        assertThat(Duration.between(Instant.now(), withDefault.expiresAt()).toSeconds()).isBetween(880L, 900L);
        assertThat(Duration.between(Instant.now(), capped.expiresAt()).toSeconds()).isBetween(86_300L, 86_400L);
        assertThat(Duration.between(Instant.now(), short_.expiresAt()).toSeconds()).isBetween(50L, 60L);
    }
}

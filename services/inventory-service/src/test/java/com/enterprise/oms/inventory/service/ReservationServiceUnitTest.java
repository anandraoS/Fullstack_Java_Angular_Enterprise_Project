package com.enterprise.oms.inventory.service;

import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.common.events.EventPayloads.QuantityItem;
import com.enterprise.oms.inventory.cache.ProductCacheInvalidator;
import com.enterprise.oms.inventory.config.InventoryProperties;
import com.enterprise.oms.inventory.domain.Product;
import com.enterprise.oms.inventory.domain.Reservation;
import com.enterprise.oms.inventory.domain.ReservationStatus;
import com.enterprise.oms.inventory.metrics.InventoryMetrics;
import com.enterprise.oms.inventory.repo.ProductRepository;
import com.enterprise.oms.inventory.repo.ReservationClaims;
import com.enterprise.oms.inventory.repo.ReservationItemRepository;
import com.enterprise.oms.inventory.repo.ReservationRepository;
import com.enterprise.oms.inventory.repo.StockMovementRepository;
import com.enterprise.oms.inventory.reservation.ReservationOutcome;
import com.enterprise.oms.inventory.reservation.ReservationStrategies;
import com.enterprise.oms.inventory.reservation.ReservationStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ReservationService orchestration with every collaborator mocked (no Spring, no DB). */
class ReservationServiceUnitTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
    private final UUID p1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID p2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private ReservationRepository reservations;
    private ReservationClaims claims;
    private ReservationItemRepository items;
    private ProductRepository products;
    private ReservationStrategy strategy;
    private InventoryEventPublisher events;
    private ReservationService service;

    @BeforeEach
    void setUp() {
        reservations = mock(ReservationRepository.class);
        claims = mock(ReservationClaims.class);
        items = mock(ReservationItemRepository.class);
        products = mock(ProductRepository.class);
        strategy = mock(ReservationStrategy.class);
        events = mock(InventoryEventPublisher.class);
        when(strategy.name()).thenReturn("atomic");
        ReservationStrategies strategies = mock(ReservationStrategies.class);
        when(strategies.active()).thenReturn(strategy);
        service = new ReservationService(reservations, claims, items, products, mock(StockMovementRepository.class),
                strategies, events, mock(AuditService.class), new InventoryProperties(),
                new InventoryMetrics(new SimpleMeterRegistry()), mock(ProductCacheInvalidator.class),
                new ObjectMapper(), clock);
        Product active = mock(Product.class);
        when(active.isActive()).thenReturn(true);
        when(active.getId()).thenReturn(p1);
        Product active2 = mock(Product.class);
        when(active2.isActive()).thenReturn(true);
        when(active2.getId()).thenReturn(p2);
        when(products.findAllById(any())).thenReturn(List.of(active, active2));
    }

    @Test
    void mergesAndSortsLinesByProductIdBeforeCallingTheStrategy() {
        when(reservations.findByOrderId(any())).thenReturn(Optional.empty());
        when(claims.claimReserved(any(), any(), any(), any())).thenReturn(true);
        when(strategy.tryReserve(any())).thenReturn(ReservationStrategy.Result.ok());

        service.reserve(UUID.randomUUID(), List.of(new QuantityItem(p2, 1), new QuantityItem(p1, 2),
                new QuantityItem(p2, 4)), null, "corr", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<QuantityItem>> lines = ArgumentCaptor.forClass(List.class);
        verify(strategy).tryReserve(lines.capture());
        assertThat(lines.getValue()).containsExactly(new QuantityItem(p1, 2), new QuantityItem(p2, 5));
    }

    @Test
    void computesExpiryFromTheClockAndTheDefaultTtl() {
        when(reservations.findByOrderId(any())).thenReturn(Optional.empty());
        when(claims.claimReserved(any(), any(), any(), any())).thenReturn(true);
        when(strategy.tryReserve(any())).thenReturn(ReservationStrategy.Result.ok());

        ReservationOutcome out = service.reserve(UUID.randomUUID(), List.of(new QuantityItem(p1, 1)), null, "c", null);

        assertThat(out.expiresAt()).isEqualTo(Instant.parse("2026-03-01T10:15:00Z"));
    }

    @Test
    void anExistingReservationShortCircuitsEverythingAndReplaysTheOriginalOutcome() {
        UUID order = UUID.randomUUID();
        Reservation existing = mock(Reservation.class);
        UUID reservationId = UUID.randomUUID();
        when(existing.getId()).thenReturn(reservationId);
        when(existing.getOrderId()).thenReturn(order);
        when(existing.getStatus()).thenReturn(ReservationStatus.RESERVED);
        when(existing.getExpiresAt()).thenReturn(Instant.parse("2026-03-01T10:10:00Z"));
        when(reservations.findByOrderId(order)).thenReturn(Optional.of(existing));
        when(items.findByReservationIdOrderByProductIdAsc(reservationId)).thenReturn(List.of());

        ReservationOutcome out = service.reserve(order, List.of(new QuantityItem(p1, 1)), null, "c", null);

        assertThat(out.duplicate()).isTrue();
        assertThat(out.reservationId()).isEqualTo(reservationId);
        verify(strategy, never()).tryReserve(any());
        verify(claims, never()).claimReserved(any(), any(), any(), any());
        verify(events).reserved(any(), any(), any(), any(), any(), any());
    }

    @Test
    void losingTheClaimRaceReplaysTheWinnersReservation() {
        UUID order = UUID.randomUUID();
        Reservation winner = mock(Reservation.class);
        UUID winnerId = UUID.randomUUID();
        when(winner.getId()).thenReturn(winnerId);
        when(winner.getOrderId()).thenReturn(order);
        when(winner.getStatus()).thenReturn(ReservationStatus.COMMITTED);
        when(reservations.findByOrderId(order)).thenReturn(Optional.empty(), Optional.of(winner));
        when(claims.claimReserved(any(), any(), any(), any())).thenReturn(false);
        when(items.findByReservationIdOrderByProductIdAsc(winnerId)).thenReturn(List.of());

        ReservationOutcome out = service.reserve(order, List.of(new QuantityItem(p1, 1)), null, "c", null);

        assertThat(out.duplicate()).isTrue();
        assertThat(out.reservationId()).isEqualTo(winnerId);
        assertThat(out.status()).isEqualTo(ReservationStatus.COMMITTED);
        verify(strategy, never()).tryReserve(any());
    }

    @Test
    void aFailureInTheStrategyPropagatesSoTheTransactionRollsBackTheClaim() {
        when(reservations.findByOrderId(any())).thenReturn(Optional.empty());
        when(claims.claimReserved(any(), any(), any(), any())).thenReturn(true);
        when(strategy.tryReserve(any())).thenThrow(new IllegalStateException("db down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.reserve(UUID.randomUUID(), List.of(new QuantityItem(p1, 1)), null, "c", null))
                .isInstanceOf(IllegalStateException.class);
        verify(events, never()).reserved(any(), any(), any(), any(), any(), any());
        verify(events, never()).failed(any(), any(), any(), any(), any());
    }

    @Test
    void ttlIsCappedAtTheConfiguredMaximum() {
        InventoryProperties props = new InventoryProperties();
        props.setMaxReservationTtlSeconds(120);
        ReflectionTestUtils.setField(service, "props", props);
        when(reservations.findByOrderId(any())).thenReturn(Optional.empty());
        when(claims.claimReserved(any(), any(), any(), any())).thenReturn(true);
        when(strategy.tryReserve(any())).thenReturn(ReservationStrategy.Result.ok());

        ReservationOutcome out = service.reserve(UUID.randomUUID(), List.of(new QuantityItem(p1, 1)), 100_000, "c", null);

        assertThat(out.expiresAt()).isEqualTo(Instant.parse("2026-03-01T10:02:00Z"));
    }
}

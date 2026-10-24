package com.enterprise.oms.inventory.reservation;

import com.enterprise.oms.common.events.EventPayloads.QuantityItem;
import com.enterprise.oms.common.events.EventPayloads.Shortage;
import com.enterprise.oms.inventory.config.InventoryProperties;
import com.enterprise.oms.inventory.domain.Stock;
import com.enterprise.oms.inventory.metrics.InventoryMetrics;
import com.enterprise.oms.inventory.repo.StockRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pure unit tests (Mockito, no Spring, no database) of the three strategies' decision logic. */
class ReservationStrategyUnitTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private final UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private final UUID c = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private StockRepository stock;
    private ShortageCalculator shortages;

    @BeforeEach
    void mocks() {
        stock = mock(StockRepository.class);
        shortages = mock(ShortageCalculator.class);
    }

    private StockRepository.Snapshot snapshot(UUID id, int onHand, int reserved, long version) {
        StockRepository.Snapshot s = mock(StockRepository.Snapshot.class);
        when(s.getProductId()).thenReturn(id);
        when(s.getOnHand()).thenReturn(onHand);
        when(s.getReserved()).thenReturn(reserved);
        when(s.getVersion()).thenReturn(version);
        return s;
    }

    @Nested
    class Atomic {
        private AtomicUpdateReservationStrategy strategy;

        @BeforeEach
        void create() {
            strategy = new AtomicUpdateReservationStrategy(stock, shortages, clock);
        }

        @Test
        void takesEveryLineInTheGivenOrderWhenAllFit() {
            when(stock.tryReserve(any(), anyInt(), any())).thenReturn(1);

            ReservationStrategy.Result r = strategy.tryReserve(List.of(new QuantityItem(a, 2), new QuantityItem(b, 3)));

            assertThat(r.success()).isTrue();
            InOrder order = inOrder(stock);
            order.verify(stock).tryReserve(eq(a), eq(2), any());
            order.verify(stock).tryReserve(eq(b), eq(3), any());
            verify(stock, never()).release(any(), anyInt(), any());
        }

        @Test
        void givesBackLinesAlreadyTakenWhenALaterLineIsShort() {
            List<QuantityItem> lines = List.of(new QuantityItem(a, 2), new QuantityItem(b, 3), new QuantityItem(c, 4));
            when(stock.tryReserve(eq(a), anyInt(), any())).thenReturn(1);
            when(stock.tryReserve(eq(b), anyInt(), any())).thenReturn(1);
            when(stock.tryReserve(eq(c), anyInt(), any())).thenReturn(0);
            when(stock.release(any(), anyInt(), any())).thenReturn(1);
            when(shortages.shortages(lines)).thenReturn(List.of(new Shortage(c, 4, 1)));

            ReservationStrategy.Result r = strategy.tryReserve(lines);

            assertThat(r.success()).isFalse();
            assertThat(r.shortages()).containsExactly(new Shortage(c, 4, 1));
            InOrder order = inOrder(stock);
            order.verify(stock).release(eq(b), eq(3), any());   // reverse order
            order.verify(stock).release(eq(a), eq(2), any());
            verify(stock, never()).release(eq(c), anyInt(), any());
        }

        @Test
        void failsLoudlyIfCompensationCannotBeApplied() {
            when(stock.tryReserve(eq(a), anyInt(), any())).thenReturn(1);
            when(stock.tryReserve(eq(b), anyInt(), any())).thenReturn(0);
            when(stock.release(any(), anyInt(), any())).thenReturn(0);
            assertThatThrownBy(() -> strategy.tryReserve(List.of(new QuantityItem(a, 1), new QuantityItem(b, 1))))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Compensation failed");
        }
    }

    @Nested
    class Pessimistic {
        private PessimisticLockReservationStrategy strategy;

        @BeforeEach
        void create() {
            strategy = new PessimisticLockReservationStrategy(stock, clock);
        }

        private Stock stockRow(UUID id, int onHand, int reserved) {
            Stock s = new Stock(id, onHand, Instant.EPOCH);
            s.setReserved(reserved, Instant.EPOCH);
            return s;
        }

        @Test
        void locksAllRowsInOrderThenWrites() {
            Stock sa = stockRow(a, 10, 0);
            Stock sb = stockRow(b, 10, 5);
            when(stock.findForUpdate(a)).thenReturn(Optional.of(sa));
            when(stock.findForUpdate(b)).thenReturn(Optional.of(sb));

            ReservationStrategy.Result r = strategy.tryReserve(List.of(new QuantityItem(a, 4), new QuantityItem(b, 5)));

            assertThat(r.success()).isTrue();
            assertThat(sa.getReserved()).isEqualTo(4);
            assertThat(sb.getReserved()).isEqualTo(10);
            InOrder order = inOrder(stock);
            order.verify(stock).findForUpdate(a);
            order.verify(stock).findForUpdate(b);
        }

        @Test
        void writesNothingAndReportsAllShortagesFromTheLockedState() {
            Stock sa = stockRow(a, 10, 8);   // available 2
            Stock sb = stockRow(b, 10, 0);   // available 10
            when(stock.findForUpdate(a)).thenReturn(Optional.of(sa));
            when(stock.findForUpdate(b)).thenReturn(Optional.of(sb));
            when(stock.findForUpdate(c)).thenReturn(Optional.empty());

            ReservationStrategy.Result r = strategy.tryReserve(
                    List.of(new QuantityItem(a, 5), new QuantityItem(b, 5), new QuantityItem(c, 1)));

            assertThat(r.success()).isFalse();
            assertThat(r.shortages()).containsExactly(new Shortage(a, 5, 2), new Shortage(c, 1, 0));
            assertThat(sa.getReserved()).isEqualTo(8);
            assertThat(sb.getReserved()).isZero();
        }
    }

    @Nested
    class Optimistic {
        private InventoryProperties props;
        private InventoryMetrics metrics;
        private OptimisticVersionReservationStrategy strategy;

        @BeforeEach
        void create() {
            props = new InventoryProperties();
            props.setOptimisticMaxRetries(3);
            metrics = new InventoryMetrics(new SimpleMeterRegistry());
            strategy = new OptimisticVersionReservationStrategy(stock, shortages, props, metrics, clock);
        }

        @Test
        void retriesAfterALostCompareAndSetAndCountsTheConflict() {
            StockRepository.Snapshot s1 = snapshot(a, 10, 0, 1);
            StockRepository.Snapshot s2 = snapshot(a, 10, 1, 2);
            when(stock.snapshot(a)).thenReturn(Optional.of(s1), Optional.of(s2));
            when(stock.compareAndReserve(eq(a), eq(1), eq(1L), any())).thenReturn(0);   // version moved
            when(stock.compareAndReserve(eq(a), eq(1), eq(2L), any())).thenReturn(1);

            ReservationStrategy.Result r = strategy.tryReserve(List.of(new QuantityItem(a, 1)));

            assertThat(r.success()).isTrue();
            assertThat(metrics.conflictCount()).isEqualTo(1.0);
            verify(stock, times(2)).snapshot(a);
        }

        @Test
        void givesUpAfterTheConfiguredNumberOfAttempts() {
            StockRepository.Snapshot only = snapshot(a, 10, 0, 1);
            when(stock.snapshot(a)).thenReturn(Optional.of(only));
            when(stock.compareAndReserve(any(), anyInt(), anyLong(), any())).thenReturn(0);

            assertThatThrownBy(() -> strategy.tryReserve(List.of(new QuantityItem(a, 1))))
                    .isInstanceOf(ReservationContentionException.class).hasMessageContaining("gave up after 3");
            assertThat(metrics.conflictCount()).isEqualTo(3.0);
        }

        @Test
        void reportsInsufficientStockWithoutWritingAndCompensatesEarlierLines() {
            List<QuantityItem> lines = List.of(new QuantityItem(a, 1), new QuantityItem(b, 9));
            StockRepository.Snapshot sa = snapshot(a, 10, 0, 1);
            StockRepository.Snapshot sb = snapshot(b, 5, 0, 1);
            when(stock.snapshot(a)).thenReturn(Optional.of(sa));
            when(stock.snapshot(b)).thenReturn(Optional.of(sb));
            when(stock.compareAndReserve(eq(a), eq(1), eq(1L), any())).thenReturn(1);
            when(stock.release(eq(a), eq(1), any())).thenReturn(1);
            when(shortages.shortages(lines)).thenReturn(List.of(new Shortage(b, 9, 5)));

            ReservationStrategy.Result r = strategy.tryReserve(lines);

            assertThat(r.success()).isFalse();
            assertThat(r.shortages()).containsExactly(new Shortage(b, 9, 5));
            verify(stock, never()).compareAndReserve(eq(b), anyInt(), anyLong(), any());
            verify(stock).release(eq(a), eq(1), any());
            assertThat(metrics.conflictCount()).isZero();
        }

        @Test
        void aMissingStockRowIsAShortage() {
            when(stock.snapshot(a)).thenReturn(Optional.empty());
            when(shortages.shortages(any())).thenReturn(List.of(new Shortage(a, 1, 0)));
            assertThat(strategy.tryReserve(List.of(new QuantityItem(a, 1))).success()).isFalse();
        }
    }

    @Test
    void registryPicksTheConfiguredStrategyAndRejectsUnknownNames() {
        InventoryProperties props = new InventoryProperties();
        InventoryMetrics metrics = new InventoryMetrics(new SimpleMeterRegistry());
        List<ReservationStrategy> all = List.of(new AtomicUpdateReservationStrategy(stock, shortages, clock),
                new PessimisticLockReservationStrategy(stock, clock),
                new OptimisticVersionReservationStrategy(stock, shortages, props, metrics, clock));

        props.setReservationStrategy("Pessimistic");
        ReservationStrategies registry = new ReservationStrategies(all, props);
        assertThat(registry.active().name()).isEqualTo("pessimistic");
        registry.select("optimistic");
        assertThat(registry.active()).isInstanceOf(OptimisticVersionReservationStrategy.class);

        props.setReservationStrategy("serializable");
        assertThatThrownBy(() -> new ReservationStrategies(all, props)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serializable");
    }
}

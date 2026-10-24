package com.enterprise.oms.order.saga;

import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.common.error.InvalidStateTransitionException;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.order.config.SagaProperties;
import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.domain.SagaState;
import com.enterprise.oms.order.domain.SagaStep;
import com.enterprise.oms.order.entity.CustomerOrder;
import com.enterprise.oms.order.entity.OrderStatusHistory;
import com.enterprise.oms.order.entity.SagaInstance;
import com.enterprise.oms.order.entity.SagaStepLog;
import com.enterprise.oms.order.entity.ShippingAddress;
import com.enterprise.oms.order.repository.OrderRepository;
import com.enterprise.oms.order.repository.OrderStatusHistoryRepository;
import com.enterprise.oms.order.repository.SagaInstanceRepository;
import com.enterprise.oms.order.repository.SagaStepLogRepository;
import com.enterprise.oms.order.service.OrderMetrics;
import com.enterprise.oms.order.service.OrderTransitions;
import com.enterprise.oms.order.service.RetryingTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Orchestrator decisions with every collaborator mocked: what gets sent, what state results. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderSagaOrchestratorTest {

    @Mock OrderRepository orders;
    @Mock SagaInstanceRepository sagas;
    @Mock SagaStepLogRepository stepLog;
    @Mock OrderStatusHistoryRepository history;
    @Mock SagaMessaging messaging;
    @Mock InventoryClient inventory;
    @Mock AuditService audit;
    @Mock OrderMetrics metrics;
    @Mock PlatformTransactionManager tm;

    private final SagaProperties props = new SagaProperties();
    private OrderSagaOrchestrator saga;
    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void wire() {
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        OrderTransitions transitions = new OrderTransitions(history, audit, metrics);
        saga = new OrderSagaOrchestrator(orders, sagas, stepLog, transitions, messaging, inventory, audit, metrics,
                props, new RetryingTransactions(tm));
    }

    private CustomerOrder order(OrderStatus status) {
        CustomerOrder o = new CustomerOrder(UUID.randomUUID(), UUID.randomUUID(), "buyer@example.test",
                new ShippingAddress("1 Main", "Berlin", "10115", "DE"), "corr-1", Instant.now().minusSeconds(5));
        o.addItem(UUID.randomUUID(), 2);
        if (status != OrderStatus.CREATED) {
            o.changeStatus(status, null, Instant.now());
        }
        when(orders.findWithItemsById(o.getId())).thenReturn(Optional.of(o));
        when(orders.findById(o.getId())).thenReturn(Optional.of(o));
        return o;
    }

    private SagaInstance sagaOf(CustomerOrder o) {
        SagaInstance s = new SagaInstance(o.getId(), Instant.now());
        when(sagas.findByOrderId(o.getId())).thenReturn(Optional.of(s));
        return s;
    }

    private static InventoryClient.Line okLine(CustomerOrder o, String price) {
        return new InventoryClient.Line(o.getItems().get(0).getProductId(), "SKU-1", "Widget", new BigDecimal(price), 50,
                true, true, null);
    }

    // ---- inventory events ----------------------------------------------------------------------------------------

    @Test
    void reservationConfirmsTheOrderAndStartsFulfillment() {
        CustomerOrder o = order(OrderStatus.VALIDATED);
        SagaInstance s = sagaOf(o);
        UUID reservation = UUID.randomUUID();

        saga.onInventoryReserved(eventId, new EventPayloads.InventoryReserved(o.getId(), reservation, List.of(), Instant.now()));

        assertThat(o.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(s.getReservationId()).isEqualTo(reservation);
        assertThat(s.getCurrentStep()).isEqualTo(SagaStep.FULFILL);
        verify(messaging).orderConfirmed(o, eventId);
        verify(messaging).startFulfillment(o, eventId);
        ArgumentCaptor<OrderStatusHistory> rows = ArgumentCaptor.forClass(OrderStatusHistory.class);
        verify(history, org.mockito.Mockito.times(2)).save(rows.capture());
        assertThat(rows.getAllValues()).extracting(OrderStatusHistory::getToStatus)
                .containsExactly(OrderStatus.INVENTORY_RESERVED, OrderStatus.CONFIRMED);
    }

    @Test
    void aReservationForACancelledOrderIsReleasedNotConfirmed() {
        CustomerOrder o = order(OrderStatus.CANCELLED);
        sagaOf(o);

        saga.onInventoryReserved(eventId, new EventPayloads.InventoryReserved(o.getId(), UUID.randomUUID(), List.of(), Instant.now()));

        assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(messaging).releaseInventory(o, "CANCELLATION", eventId);
        verify(messaging, never()).orderConfirmed(any(), any());
        verify(messaging, never()).startFulfillment(any(), any());
        verify(metrics).compensation("LATE_RESERVATION");
    }

    @Test
    void aReplayedReservationAfterConfirmationSendsNothing() {
        CustomerOrder o = order(OrderStatus.CONFIRMED);
        sagaOf(o);

        saga.onInventoryReserved(eventId, new EventPayloads.InventoryReserved(o.getId(), UUID.randomUUID(), List.of(), Instant.now()));

        assertThat(o.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        org.mockito.Mockito.verifyNoInteractions(messaging);
    }

    @Test
    void anEventForAnUnknownOrderIsIgnoredAndCounted() {
        UUID ghost = UUID.randomUUID();
        when(orders.findWithItemsById(ghost)).thenReturn(Optional.empty());

        saga.onInventoryReserved(eventId, new EventPayloads.InventoryReserved(ghost, UUID.randomUUID(), List.of(), Instant.now()));

        verify(metrics).ignoredEvent("InventoryReserved.unknownOrder");
        org.mockito.Mockito.verifyNoInteractions(messaging);
    }

    @Test
    void reservationFailureRejectsWithTheShortage() {
        CustomerOrder o = order(OrderStatus.VALIDATED);
        SagaInstance s = sagaOf(o);
        UUID product = o.getItems().get(0).getProductId();

        saga.onInventoryReservationFailed(eventId, new EventPayloads.InventoryReservationFailed(o.getId(),
                "INSUFFICIENT_STOCK", List.of(new EventPayloads.Shortage(product, 2, 1))));

        assertThat(o.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(o.getStatusReason()).contains("requested 2 available 1");
        assertThat(s.getState()).isEqualTo(SagaState.ABORTED);
        verify(messaging).orderRejected(eq(o), org.mockito.ArgumentMatchers.contains("Insufficient stock"), eq(eventId));
    }

    @Test
    void releaseAfterCancellationCancelsTheOrder() {
        CustomerOrder o = order(OrderStatus.COMPENSATION_PENDING);
        o.changeStatus(OrderStatus.COMPENSATION_PENDING, "customer asked", Instant.now());
        SagaInstance s = sagaOf(o);
        s.startCompensation("CANCELLATION", Instant.now());

        saga.onInventoryReleased(eventId, new EventPayloads.InventoryReleased(o.getId(), UUID.randomUUID(), "CANCELLATION"));

        assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(o.getStatusReason()).isEqualTo("customer asked");
        assertThat(s.getState()).isEqualTo(SagaState.COMPENSATED);
        assertThat(s.isReservationReleased()).isTrue();
        verify(messaging).orderCancelled(o, "customer asked", eventId);
    }

    @Test
    void releaseAfterFulfillmentFailureFailsTheOrder() {
        CustomerOrder o = order(OrderStatus.COMPENSATION_PENDING);
        SagaInstance s = sagaOf(o);
        s.startCompensation("FULFILLMENT_FAILURE", Instant.now());

        saga.onInventoryReleased(eventId, new EventPayloads.InventoryReleased(o.getId(), UUID.randomUUID(), "FULFILLMENT_FAILURE"));

        assertThat(o.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(s.getState()).isEqualTo(SagaState.FAILED);
        verify(messaging).orderFailed(eq(o), anyString(), eq(eventId));
        verify(messaging, never()).orderCancelled(any(), any(), any());
    }

    @Test
    void expiredReservationFailsAnOrderThatWasNotYetConfirmed() {
        CustomerOrder o = order(OrderStatus.VALIDATED);
        SagaInstance s = sagaOf(o);

        saga.onInventoryReleased(eventId, new EventPayloads.InventoryReleased(o.getId(), UUID.randomUUID(), "EXPIRED"));

        assertThat(o.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(s.getState()).isEqualTo(SagaState.FAILED);
        verify(messaging).orderFailed(eq(o), org.mockito.ArgumentMatchers.contains("expired"), eq(eventId));
    }

    // ---- fulfillment events --------------------------------------------------------------------------------------

    @Test
    void completedFulfillmentFulfillsTheOrder() {
        CustomerOrder o = order(OrderStatus.CONFIRMED);
        SagaInstance s = sagaOf(o);

        saga.onFulfillmentCompleted(eventId, new EventPayloads.FulfillmentCompleted(o.getId(), UUID.randomUUID(), "TRK-7"));

        assertThat(o.getStatus()).isEqualTo(OrderStatus.FULFILLED);
        assertThat(s.getState()).isEqualTo(SagaState.COMPLETED);
        verify(messaging).orderFulfilled(o, "TRK-7", eventId);
        verify(metrics).orderFinished(eq(OrderStatus.FULFILLED), any());
    }

    @Test
    void failedFulfillmentStartsTheCompensation() {
        CustomerOrder o = order(OrderStatus.CONFIRMED);
        SagaInstance s = sagaOf(o);

        saga.onFulfillmentFailed(eventId, new EventPayloads.FulfillmentFailed(o.getId(), UUID.randomUUID(), "no courier", 3));

        assertThat(o.getStatus()).isEqualTo(OrderStatus.COMPENSATION_PENDING);
        assertThat(s.getState()).isEqualTo(SagaState.COMPENSATING);
        assertThat(s.getReason()).isEqualTo("FULFILLMENT_FAILURE");
        verify(messaging).releaseInventory(o, "FULFILLMENT_FAILURE", eventId);
        verify(metrics).compensation("FULFILLMENT_FAILURE");
    }

    @Test
    void aStaleFulfillmentFailureForAFulfilledOrderChangesNothing() {
        CustomerOrder o = order(OrderStatus.FULFILLED);
        sagaOf(o);

        saga.onFulfillmentFailed(eventId, new EventPayloads.FulfillmentFailed(o.getId(), UUID.randomUUID(), "late", 1));

        assertThat(o.getStatus()).isEqualTo(OrderStatus.FULFILLED);
        org.mockito.Mockito.verifyNoInteractions(messaging);
        verify(metrics).ignoredEvent("FULFILLMENT_FAILED");
    }

    // ---- validation ----------------------------------------------------------------------------------------------

    @Test
    void validResultPricesTheOrderAndMovesOn() {
        CustomerOrder o = order(OrderStatus.CREATED);
        SagaInstance s = sagaOf(o);

        saga.applyValidation(o.getId(), new InventoryClient.ValidationResult(true, List.of(okLine(o, "7.25"))));

        assertThat(o.getStatus()).isEqualTo(OrderStatus.VALIDATED);
        assertThat(o.getTotalAmount()).isEqualByComparingTo("14.50");
        assertThat(o.getItems().get(0).getLineTotal()).isEqualByComparingTo("14.50");
        assertThat(s.getCurrentStep()).isEqualTo(SagaStep.RESERVE_INVENTORY);
        verify(messaging).orderCreated(o, null);
        verify(messaging).reserveInventory(o, null);
    }

    @Test
    void aMissingLineRejectsTheOrder() {
        CustomerOrder o = order(OrderStatus.CREATED);
        SagaInstance s = sagaOf(o);

        saga.applyValidation(o.getId(), new InventoryClient.ValidationResult(true, List.of()));

        assertThat(o.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(o.getStatusReason()).contains("not validated by inventory");
        assertThat(s.getState()).isEqualTo(SagaState.ABORTED);
        verify(messaging).orderRejected(eq(o), anyString(), eq(null));
        verify(messaging, never()).reserveInventory(any(), any());
    }

    @Test
    void anInactiveProductRejectsTheOrderWithItsSku() {
        CustomerOrder o = order(OrderStatus.CREATED);
        sagaOf(o);
        InventoryClient.Line inactive = new InventoryClient.Line(o.getItems().get(0).getProductId(), "SKU-OLD", "Old", new BigDecimal("1"),
                0, false, false, null);

        saga.applyValidation(o.getId(), new InventoryClient.ValidationResult(false, List.of(inactive)));

        assertThat(o.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(o.getStatusReason()).contains("SKU-OLD").contains("inactive");
    }

    @Test
    void aValidationResultForAnOrderThatMovedOnIsDropped() {
        CustomerOrder o = order(OrderStatus.CANCELLED);
        sagaOf(o);

        saga.applyValidation(o.getId(), new InventoryClient.ValidationResult(true, List.of(okLine(o, "1.00"))));

        assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        org.mockito.Mockito.verifyNoInteractions(messaging);
    }

    @Test
    void anOutageIncrementsTheAttemptCounterAndRecordsTheError() {
        CustomerOrder o = order(OrderStatus.CREATED);
        SagaInstance s = sagaOf(o);
        when(inventory.validate(any())).thenThrow(new InventoryUnavailableException("boom", null));

        saga.validate(o.getId());

        assertThat(o.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(s.getAttempts()).isEqualTo(1);
        assertThat(s.getLastError()).contains("InventoryUnavailableException").contains("boom");
        ArgumentCaptor<SagaStepLog> log = ArgumentCaptor.forClass(SagaStepLog.class);
        verify(stepLog).save(log.capture());
        assertThat(log.getValue().getStep()).isEqualTo(SagaStep.VALIDATE);
    }

    @Test
    void theLastAllowedAttemptFailsTheOrder() {
        props.setMaxAttempts(1);
        CustomerOrder o = order(OrderStatus.CREATED);
        sagaOf(o);
        when(inventory.validate(any())).thenThrow(new InventoryUnavailableException("down", null));

        saga.validate(o.getId());

        assertThat(o.getStatus()).isEqualTo(OrderStatus.FAILED);
        verify(messaging).orderFailed(eq(o), org.mockito.ArgumentMatchers.contains("Validation failed after 1 attempts"), eq(null));
        verify(messaging, never()).releaseInventory(any(), any(), any());   // nothing could have been reserved
    }

    @Test
    void validationSkipsOrdersThatAreNotCreated() {
        CustomerOrder o = order(OrderStatus.VALIDATED);
        sagaOf(o);

        saga.validate(o.getId());

        org.mockito.Mockito.verifyNoInteractions(inventory);
    }

    // ---- cancel and recovery -------------------------------------------------------------------------------------

    @Test
    void cancelBeforeAReservationIsImmediate() {
        CustomerOrder o = order(OrderStatus.VALIDATED);
        SagaInstance s = sagaOf(o);

        saga.cancel(o, s, "changed mind");

        assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(s.getState()).isEqualTo(SagaState.ABORTED);
        verify(messaging).orderCancelled(o, "changed mind", null);
        verify(messaging, never()).releaseInventory(any(), any(), any());
    }

    @Test
    void cancelAfterAReservationCompensates() {
        CustomerOrder o = order(OrderStatus.CONFIRMED);
        SagaInstance s = sagaOf(o);

        saga.cancel(o, s, "changed mind");

        assertThat(o.getStatus()).isEqualTo(OrderStatus.COMPENSATION_PENDING);
        assertThat(s.getState()).isEqualTo(SagaState.COMPENSATING);
        verify(messaging).releaseInventory(o, "CANCELLATION", null);
        verify(messaging, never()).orderCancelled(any(), any(), any());
    }

    @Test
    void cancelOfAFulfilledOrderIsAConflictAndSendsNothing() {
        CustomerOrder o = order(OrderStatus.FULFILLED);
        SagaInstance s = sagaOf(o);

        assertThatThrownBy(() -> saga.cancel(o, s, "too late")).isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("FULFILLED").hasMessageContaining("CANCELLED");
        org.mockito.Mockito.verifyNoInteractions(messaging);
    }

    @Test
    void recoveryGivesUpOnAConfirmedOrderAfterTheMaximumAttempts() {
        CustomerOrder o = order(OrderStatus.CONFIRMED);
        SagaInstance s = sagaOf(o);
        for (int i = 0; i < props.getMaxAttempts(); i++) {
            s.incrementAttempts(Instant.now());
        }

        assertThat(saga.recover(o.getId(), false)).isEqualTo(OrderSagaOrchestrator.RedriveResult.EXHAUSTED);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.COMPENSATION_PENDING);
        verify(messaging).releaseInventory(o, "FULFILLMENT_FAILURE", null);
        verify(metrics).alert("exhausted-confirmed");
    }

    @Test
    void recoveryResendsTheCommandOfTheCurrentStep() {
        CustomerOrder validated = order(OrderStatus.VALIDATED);
        sagaOf(validated);
        CustomerOrder confirmed = order(OrderStatus.CONFIRMED);
        sagaOf(confirmed);

        saga.recover(validated.getId(), false);
        saga.recover(confirmed.getId(), false);

        verify(messaging).reserveInventory(validated, null);
        verify(messaging).startFulfillment(confirmed, null);
    }
}

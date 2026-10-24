package com.enterprise.oms.order.service;

import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.common.error.ConflictException;
import com.enterprise.oms.common.error.ForbiddenException;
import com.enterprise.oms.common.error.IdempotencyKeyRequiredException;
import com.enterprise.oms.common.error.IdempotencyKeyReusedException;
import com.enterprise.oms.common.error.NotFoundException;
import com.enterprise.oms.common.error.RequestValidationException;
import com.enterprise.oms.common.events.EventJson;
import com.enterprise.oms.common.security.CurrentUser;
import com.enterprise.oms.common.web.IdempotencyKeys;
import com.enterprise.oms.order.api.dto.AddressDto;
import com.enterprise.oms.order.api.dto.CreateOrderRequest;
import com.enterprise.oms.order.api.dto.OrderItemRequest;
import com.enterprise.oms.order.api.dto.OrderView;
import com.enterprise.oms.order.domain.CustomerTier;
import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.entity.Customer;
import com.enterprise.oms.order.entity.CustomerOrder;
import com.enterprise.oms.order.entity.IdempotencyKeyRecord;
import com.enterprise.oms.order.entity.SagaInstance;
import com.enterprise.oms.order.entity.ShippingAddress;
import com.enterprise.oms.order.repository.CustomerRepository;
import com.enterprise.oms.order.repository.IdempotencyKeyRepository;
import com.enterprise.oms.order.repository.OrderRepository;
import com.enterprise.oms.order.repository.SagaInstanceRepository;
import com.enterprise.oms.order.saga.OrderAccepted;
import com.enterprise.oms.order.saga.OrderSagaOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderCommandServiceTest {

    @Mock CustomerRepository customers;
    @Mock OrderRepository orders;
    @Mock SagaInstanceRepository sagas;
    @Mock IdempotencyKeyRepository idempotencyKeys;
    @Mock OrderTransitions transitions;
    @Mock OrderSagaOrchestrator saga;
    @Mock AuditService audit;
    @Mock ApplicationEventPublisher events;
    @Mock PlatformTransactionManager tm;

    private OrderCommandService service;
    private final UUID customerId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final String key = "key-12345678";

    private CurrentUser customer() {
        return new CurrentUser("customer-" + customerId, Set.of("CUSTOMER"), customerId);
    }

    private CurrentUser staff() {
        return new CurrentUser("operator-1", Set.of("OPERATOR"), null);
    }

    private CreateOrderRequest request(int quantity) {
        return new CreateOrderRequest(customerId, List.of(new OrderItemRequest(productId, quantity)),
                new AddressDto("1 Main", "Berlin", "10115", "DE"));
    }

    @BeforeEach
    void wire() {
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new OrderCommandService(customers, orders, sagas, idempotencyKeys, transitions, saga, audit, events,
                EventJson.mapper(), new RetryingTransactions(tm), tm);
        when(customers.findById(customerId)).thenReturn(Optional.of(
                new Customer(customerId, "Acme", "acme@example.test", CustomerTier.GOLD, Instant.now())));
        when(idempotencyKeys.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(orders.save(any())).thenAnswer(i -> i.getArgument(0));
        when(idempotencyKeys.findByScopeAndKey(anyString(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void aNewRequestCreatesTheOrderAndPublishesTheAfterCommitHook() {
        OrderCommandService.CreateResult r = service.create(request(3), key, customer());

        assertThat(r.replay()).isFalse();
        assertThat(r.order().status()).isEqualTo(OrderStatus.CREATED);
        assertThat(r.order().customerId()).isEqualTo(customerId);
        assertThat(r.order().items()).hasSize(1);
        assertThat(r.order().items().get(0).quantity()).isEqualTo(3);
        verify(saga).start(any(CustomerOrder.class));
        verify(transitions).recordCreation(any(CustomerOrder.class), anyString());
        ArgumentCaptor<OrderAccepted> hook = ArgumentCaptor.forClass(OrderAccepted.class);
        verify(events).publishEvent(hook.capture());
        assertThat(hook.getValue().orderId()).isEqualTo(r.order().id());
        ArgumentCaptor<IdempotencyKeyRecord> rec = ArgumentCaptor.forClass(IdempotencyKeyRecord.class);
        verify(idempotencyKeys).saveAndFlush(rec.capture());
        assertThat(rec.getValue().getScope()).isEqualTo(customerId.toString());
        assertThat(rec.getValue().getResponseStatus()).isEqualTo(202);      // snapshot stored on the key row
        assertThat(rec.getValue().getResponseBody()).contains(r.order().id().toString());
    }

    @Test
    void aCustomerCannotOrderForAnotherCustomer() {
        CurrentUser other = new CurrentUser("customer-x", Set.of("CUSTOMER"), UUID.randomUUID());
        assertThatThrownBy(() -> service.create(request(1), key, other)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(idempotencyKeys, orders, customers);
    }

    @Test
    void aCustomerTokenWithoutACustomerIdIsForbidden() {
        CurrentUser noClaim = new CurrentUser("customer-x", Set.of("CUSTOMER"), null);
        assertThatThrownBy(() -> service.create(request(1), key, noClaim)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aServiceTokenCannotCreateOrders() {
        CurrentUser svc = new CurrentUser("svc-inventory", Set.of("SERVICE"), null);
        assertThatThrownBy(() -> service.create(request(1), key, svc)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void staffMayOrderForAnyCustomer() {
        assertThat(service.create(request(1), key, staff()).replay()).isFalse();
    }

    @Test
    void theKeyIsValidatedFirst() {
        assertThatThrownBy(() -> service.create(request(1), null, customer())).isInstanceOf(IdempotencyKeyRequiredException.class);
        assertThatThrownBy(() -> service.create(request(1), "  ", customer())).isInstanceOf(IdempotencyKeyRequiredException.class);
        assertThatThrownBy(() -> service.create(request(1), "bad key!", customer())).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void duplicateProductsAreRejected() {
        CreateOrderRequest dup = new CreateOrderRequest(customerId,
                List.of(new OrderItemRequest(productId, 1), new OrderItemRequest(productId, 2)),
                new AddressDto("1 Main", "Berlin", "10115", "DE"));
        assertThatThrownBy(() -> service.create(dup, key, customer())).isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("Validation failed");
    }

    @Test
    void anUnknownCustomerIsNotFoundAndTheKeyIsNotStored() {
        UUID ghost = UUID.randomUUID();
        CreateOrderRequest r = new CreateOrderRequest(ghost, List.of(new OrderItemRequest(productId, 1)),
                new AddressDto("1 Main", "Berlin", "10115", "DE"));
        assertThatThrownBy(() -> service.create(r, key, staff())).isInstanceOf(NotFoundException.class);
        verify(idempotencyKeys, never()).saveAndFlush(any());
    }

    private IdempotencyKeyRecord stored(CreateOrderRequest forRequest, UUID orderId) throws Exception {
        IdempotencyKeyRecord rec = new IdempotencyKeyRecord(customerId.toString(), key,
                IdempotencyKeys.fingerprint(forRequest.canonical()), Instant.now());
        CustomerOrder o = new CustomerOrder(orderId, customerId, "acme@example.test",
                new ShippingAddress("1 Main", "Berlin", "10115", "DE"), "corr", Instant.now());
        o.addItem(productId, forRequest.items().get(0).quantity());
        rec.complete(orderId, 202, EventJson.mapper().writeValueAsString(OrderView.of(o)));
        return rec;
    }

    @Test
    void sameKeyAndSameRequestReplaysTheStoredResponse() throws Exception {
        UUID original = UUID.randomUUID();
        when(idempotencyKeys.findByScopeAndKey(customerId.toString(), key)).thenReturn(Optional.of(stored(request(2), original)));

        OrderCommandService.CreateResult r = service.create(request(2), key, customer());

        assertThat(r.replay()).isTrue();
        assertThat(r.order().id()).isEqualTo(original);
        verify(orders, never()).save(any());
        verifyNoInteractions(saga, events);
    }

    @Test
    void sameKeyWithADifferentRequestIsRejected() throws Exception {
        when(idempotencyKeys.findByScopeAndKey(customerId.toString(), key))
                .thenReturn(Optional.of(stored(request(2), UUID.randomUUID())));

        assertThatThrownBy(() -> service.create(request(3), key, customer())).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(orders, never()).save(any());
    }

    @Test
    void theLoserOfARaceReplaysTheWinnersResponse() throws Exception {
        UUID winner = UUID.randomUUID();
        when(idempotencyKeys.findByScopeAndKey(customerId.toString(), key))
                .thenReturn(Optional.empty(), Optional.of(stored(request(2), winner)));
        when(idempotencyKeys.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_idempotency_scope_key"));

        OrderCommandService.CreateResult r = service.create(request(2), key, customer());

        assertThat(r.replay()).isTrue();
        assertThat(r.order().id()).isEqualTo(winner);
        verify(orders, never()).save(any());
    }

    @Test
    void ifTheWinnerRolledBackTheLoserRetriesAndEventuallyReportsConflict() {
        when(idempotencyKeys.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("conflict"));

        assertThatThrownBy(() -> service.create(request(2), key, customer())).isInstanceOf(ConflictException.class);
        verify(idempotencyKeys, times(3)).saveAndFlush(any());
    }

    // ---- cancel --------------------------------------------------------------------------------------------------

    private CustomerOrder existingOrder() {
        CustomerOrder o = new CustomerOrder(UUID.randomUUID(), customerId, "acme@example.test",
                new ShippingAddress("1 Main", "Berlin", "10115", "DE"), "corr", Instant.now());
        o.addItem(productId, 1);
        when(orders.findWithItemsById(o.getId())).thenReturn(Optional.of(o));
        when(sagas.findByOrderId(o.getId())).thenReturn(Optional.of(new SagaInstance(o.getId(), Instant.now())));
        return o;
    }

    @Test
    void anotherCustomersOrderCannotBeCancelledAndLooksNonexistent() {
        CustomerOrder o = existingOrder();
        CurrentUser stranger = new CurrentUser("customer-y", Set.of("CUSTOMER"), UUID.randomUUID());

        assertThatThrownBy(() -> service.cancel(o.getId(), "x", stranger)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(saga);
    }

    @Test
    void theOwnerAndStaffCancelWithTheirReason() {
        CustomerOrder o = existingOrder();

        service.cancel(o.getId(), "  changed my mind ", customer());
        service.cancel(o.getId(), null, staff());

        ArgumentCaptor<String> reasons = ArgumentCaptor.forClass(String.class);
        verify(saga, times(2)).cancel(eq(o), any(SagaInstance.class), reasons.capture());
        assertThat(reasons.getAllValues()).containsExactly("changed my mind", "Cancelled by staff");
        verify(audit, times(2)).record(eq("Order"), eq(o.getId()), eq("ORDER_CANCEL_REQUESTED"), any());
    }

    @Test
    void aBlankReasonFromACustomerGetsTheCustomerDefault() {
        CustomerOrder o = existingOrder();
        service.cancel(o.getId(), "   ", customer());
        verify(saga).cancel(eq(o), any(SagaInstance.class), eq("Cancelled by customer"));
    }

    @Test
    void cancellingAnUnknownOrderIsNotFound() {
        UUID ghost = UUID.randomUUID();
        when(orders.findWithItemsById(ghost)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancel(ghost, "x", staff())).isInstanceOf(NotFoundException.class);
    }
}

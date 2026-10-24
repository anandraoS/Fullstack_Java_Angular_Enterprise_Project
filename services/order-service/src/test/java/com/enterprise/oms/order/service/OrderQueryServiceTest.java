package com.enterprise.oms.order.service;

import com.enterprise.oms.common.error.ForbiddenException;
import com.enterprise.oms.common.error.NotFoundException;
import com.enterprise.oms.common.error.RequestValidationException;
import com.enterprise.oms.common.security.CurrentUser;
import com.enterprise.oms.order.config.SagaProperties;
import com.enterprise.oms.order.entity.CustomerOrder;
import com.enterprise.oms.order.entity.ShippingAddress;
import com.enterprise.oms.order.repository.OrderRepository;
import com.enterprise.oms.order.repository.OrderStatusHistoryRepository;
import com.enterprise.oms.order.repository.SagaInstanceRepository;
import com.enterprise.oms.order.repository.SagaStepLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Resource-level authorization of the read side (the SQL filter itself is covered by OrderRepositoryTest). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderQueryServiceTest {

    @Mock OrderRepository orders;
    @Mock OrderStatusHistoryRepository history;
    @Mock SagaInstanceRepository sagas;
    @Mock SagaStepLogRepository stepLog;

    private OrderQueryService service;
    private final UUID ownerId = UUID.randomUUID();
    private CustomerOrder order;

    @BeforeEach
    void wire() {
        service = new OrderQueryService(orders, history, sagas, stepLog, new SagaProperties());
        order = new CustomerOrder(UUID.randomUUID(), ownerId, "o@example.test",
                new ShippingAddress("1 Main", "Berlin", "10115", "DE"), "corr", Instant.now());
        order.addItem(UUID.randomUUID(), 1);
        when(orders.findWithItemsById(order.getId())).thenReturn(Optional.of(order));
    }

    private static CurrentUser customer(UUID id) {
        return new CurrentUser("customer-" + id, Set.of("CUSTOMER"), id);
    }

    @Test
    void theOwnerSeesTheirOrder() {
        assertThat(service.get(order.getId(), customer(ownerId)).id()).isEqualTo(order.getId());
    }

    @Test
    void operatorsAndAdminsSeeAnyOrder() {
        assertThat(service.get(order.getId(), new CurrentUser("op", Set.of("OPERATOR"), null)).id()).isEqualTo(order.getId());
        assertThat(service.get(order.getId(), new CurrentUser("ad", Set.of("ADMIN"), null)).id()).isEqualTo(order.getId());
    }

    @Test
    void anotherCustomerGetsNotFoundForGetAndHistory() {
        CurrentUser stranger = customer(UUID.randomUUID());
        assertThatThrownBy(() -> service.get(order.getId(), stranger)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.history(order.getId(), stranger)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(history);
    }

    @Test
    void aCustomerTokenWithoutACustomerClaimSeesNothing() {
        CurrentUser noClaim = new CurrentUser("c", Set.of("CUSTOMER"), null);
        assertThatThrownBy(() -> service.get(order.getId(), noClaim)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.search(new OrderQueryService.OrderSearch(null, null, null, null, null),
                PageRequest.of(0, 10), noClaim)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aServiceTokenIsNotAStaffMember() {
        CurrentUser svc = new CurrentUser("svc-x", Set.of("SERVICE"), null);
        assertThatThrownBy(() -> service.get(order.getId(), svc)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.search(new OrderQueryService.OrderSearch(null, null, null, null, null),
                PageRequest.of(0, 10), svc)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void anInvertedDateRangeIsAValidationError() {
        CurrentUser staff = new CurrentUser("op", Set.of("OPERATOR"), null);
        Instant now = Instant.now();
        assertThatThrownBy(() -> service.search(new OrderQueryService.OrderSearch(null, null, now, now.minusSeconds(1), null),
                PageRequest.of(0, 10), staff)).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void likeWildcardsInSearchTextAreEscaped() {
        assertThat(OrderSpecifications.escapeLike("50%_off\\x")).isEqualTo("50\\%\\_off\\\\x");
    }
}

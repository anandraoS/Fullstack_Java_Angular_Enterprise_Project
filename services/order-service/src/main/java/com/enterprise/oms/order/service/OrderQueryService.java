package com.enterprise.oms.order.service;

import com.enterprise.oms.common.error.ForbiddenException;
import com.enterprise.oms.common.error.NotFoundException;
import com.enterprise.oms.common.error.RequestValidationException;
import com.enterprise.oms.common.security.CurrentUser;
import com.enterprise.oms.common.web.PageResponse;
import com.enterprise.oms.order.api.dto.HistoryView;
import com.enterprise.oms.order.api.dto.OrderView;
import com.enterprise.oms.order.api.dto.SagaView;
import com.enterprise.oms.order.config.SagaProperties;
import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.entity.CustomerOrder;
import com.enterprise.oms.order.repository.OrderRepository;
import com.enterprise.oms.order.repository.OrderStatusHistoryRepository;
import com.enterprise.oms.order.repository.SagaInstanceRepository;
import com.enterprise.oms.order.repository.SagaStepLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read side of the orders API with resource-level authorization: a CUSTOMER only ever sees their own orders (another
 * customer's order is 404, never 403, so ids cannot be enumerated); OPERATOR/ADMIN see everything.
 */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    public record OrderSearch(OrderStatus status, UUID customerId, Instant from, Instant to, String q) {
    }

    private final OrderRepository orders;
    private final OrderStatusHistoryRepository history;
    private final SagaInstanceRepository sagas;
    private final SagaStepLogRepository stepLog;
    private final SagaProperties sagaProps;

    public OrderQueryService(OrderRepository orders, OrderStatusHistoryRepository history,
                             SagaInstanceRepository sagas, SagaStepLogRepository stepLog, SagaProperties sagaProps) {
        this.orders = orders;
        this.history = history;
        this.sagas = sagas;
        this.stepLog = stepLog;
        this.sagaProps = sagaProps;
    }

    public OrderView get(UUID id, CurrentUser user) {
        return OrderView.of(visibleOrder(id, user));
    }

    public List<HistoryView> history(UUID id, CurrentUser user) {
        visibleOrder(id, user);
        return history.findByOrderIdOrderByOccurredAtAscIdAsc(id).stream().map(HistoryView::of).toList();
    }

    public PageResponse<OrderView> search(OrderSearch criteria, Pageable pageable, CurrentUser user) {
        if (criteria.from() != null && criteria.to() != null && criteria.from().isAfter(criteria.to())) {
            throw new RequestValidationException("from", "must not be after 'to'");
        }
        UUID customerFilter = criteria.customerId();
        if (!user.isStaff()) {
            // forced filter: a customer can only query their own orders, whatever they pass
            if (!user.isCustomer() || user.customerId() == null) {
                throw new ForbiddenException("No customer identity in the token");
            }
            customerFilter = user.customerId();
        }
        Specification<CustomerOrder> spec = Specification.where(OrderSpecifications.customer(customerFilter))
                .and(OrderSpecifications.status(criteria.status()))
                .and(OrderSpecifications.createdFrom(criteria.from()))
                .and(OrderSpecifications.createdTo(criteria.to()))
                .and(OrderSpecifications.text(criteria.q()));
        return page(spec, pageable);
    }

    /** REJECTED, FAILED and COMPENSATION_PENDING orders that stopped moving: the operator's worklist. */
    public PageResponse<OrderView> failed(Pageable pageable) {
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(sagaProps.getStuckAfterSeconds()));
        return page(OrderSpecifications.failedOrStuck(cutoff), pageable);
    }

    public SagaView saga(UUID orderId) {
        var saga = sagas.findByOrderId(orderId).orElseThrow(() -> new NotFoundException("Saga for order", orderId));
        return SagaView.of(saga, stepLog.findByOrderIdOrderByIdAsc(orderId));
    }

    /**
     * Page of orders without N+1: (1) page + count query on the order table only, (2) ONE query that fetch-joins
     * the items of exactly the page's ids. Paging a collection fetch join directly would page in memory.
     */
    private PageResponse<OrderView> page(Specification<CustomerOrder> spec, Pageable pageable) {
        Pageable stable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                pageable.getSort().and(Sort.by("id")));    // deterministic paging when sort keys tie
        Page<CustomerOrder> page = orders.findAll(spec, stable);
        List<UUID> ids = page.getContent().stream().map(CustomerOrder::getId).toList();
        Map<UUID, CustomerOrder> withItems = ids.isEmpty() ? Map.of()
                : orders.findAllWithItemsByIdIn(ids).stream().collect(Collectors.toMap(CustomerOrder::getId, Function.identity()));
        List<OrderView> content = ids.stream().map(withItems::get).map(OrderView::of).toList();
        return new PageResponse<>(content, page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }

    private CustomerOrder visibleOrder(UUID id, CurrentUser user) {
        return orders.findWithItemsById(id).filter(o -> user.canAccessCustomer(o.getCustomerId()))
                .orElseThrow(() -> new NotFoundException("Order", id));
    }
}

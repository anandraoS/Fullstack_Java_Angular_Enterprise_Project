package com.enterprise.oms.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.order.domain.OrderStatus;
import com.enterprise.oms.order.domain.OrderTrigger;
import com.enterprise.oms.order.entity.CustomerOrder;
import com.enterprise.oms.order.entity.ShippingAddress;
import com.enterprise.oms.order.repository.OrderStatusHistoryRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Regression for an observability gap found by the live correlation-trace check: order-service logged NOTHING about an order's
 * lifecycle on the success path, so one order's correlation id could be followed through the gateway, inventory and
 * notification logs but not through the service that owns the order. Every transition now logs one line, and the line carries
 * the correlationId that is in the MDC of the calling thread.
 */
class OrderTransitionsLoggingTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(OrderTransitions.class);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        MDC.clear();
    }

    @Test
    void transitionLogsOneInfoLineWithTheCallersCorrelationId() {
        OrderTransitions transitions = new OrderTransitions(Mockito.mock(OrderStatusHistoryRepository.class),
                Mockito.mock(AuditService.class), Mockito.mock(OrderMetrics.class));
        CustomerOrder order = new CustomerOrder(UUID.randomUUID(), UUID.randomUUID(), "buyer@example.test",
                new ShippingAddress("1 Main", "Berlin", "10115", "DE"), "corr-xyz", Instant.now());
        order.addItem(UUID.randomUUID(), 1);

        MDC.put("correlationId", "corr-xyz");
        transitions.apply(order, OrderTrigger.VALIDATION_PASSED, null, "system");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.VALIDATED);
        ILoggingEvent line = appender.list.stream().filter(e -> e.getFormattedMessage().contains("CREATED -> VALIDATED"))
                .findFirst().orElseThrow();
        assertThat(line.getFormattedMessage()).contains(order.getId().toString());
        assertThat(line.getMDCPropertyMap()).containsEntry("correlationId", "corr-xyz");
        assertThat(line.getFormattedMessage()).doesNotContain("buyer@example.test"); // no PII in lifecycle logs
    }
}

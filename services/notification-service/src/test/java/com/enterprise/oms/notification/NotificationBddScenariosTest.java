package com.enterprise.oms.notification;

import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventJson;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.common.events.Topics;
import com.enterprise.oms.common.idempotency.ProcessedEvent;
import com.enterprise.oms.common.idempotency.ProcessedEventRepository;
import com.enterprise.oms.common.messaging.MessagingMetrics;
import com.enterprise.oms.notification.config.NotificationProperties;
import com.enterprise.oms.notification.domain.Notification;
import com.enterprise.oms.notification.domain.NotificationAttempt;
import com.enterprise.oms.notification.domain.NotificationRepository;
import com.enterprise.oms.notification.domain.NotificationStatus;
import com.enterprise.oms.notification.messaging.NotificationEventsListener;
import com.enterprise.oms.notification.service.NotificationDeliveryJob;
import com.enterprise.oms.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executable mirror of tests/bdd/notification-scenarios.feature. Events go through the real consumer pipeline
 * (EventRouter -> idempotent processor -> NotificationService) without a Kafka transport, and the delivery scheduler
 * is driven by hand, so the scenarios are deterministic.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Feature: Customer notifications from order and fulfillment events")
class NotificationBddScenariosTest {

    @Autowired NotificationEventsListener listener;
    @Autowired NotificationDeliveryJob job;
    @Autowired NotificationService service;
    @Autowired NotificationRepository repository;
    @Autowired ProcessedEventRepository processed;
    @Autowired MessagingMetrics metrics;
    @Autowired NotificationProperties props;

    @BeforeEach
    void background() {
        props.setSchedulerEnabled(false);
        props.setMaxAttempts(5);
        props.setSimulatedFailures(2);
        props.setBackoffInitialMs(0);
        props.setBackoffMaxMs(0);
    }

    private EventEnvelope<EventPayloads.OrderCreated> orderCreated(UUID orderId, UUID customerId, String email) {
        return EventEnvelope.forOrder(EventTypes.ORDER_CREATED, orderId, "order-service", "corr-bdd", null,
                new EventPayloads.OrderCreated(orderId, customerId, email, new BigDecimal("10.00"), "USD", List.of(),
                        "Springfield"));
    }

    private void deliver(EventEnvelope<?> env, String topic) {
        listener.route(topic, EventJson.toJson(env));
    }

    private List<Notification> of(UUID orderId) {
        return repository.findAll().stream().filter(n -> n.getOrderId().equals(orderId)).toList();
    }

    private void runDeliveryUntilIdle() {
        int guard = 0;
        while (job.runOnce() > 0 && guard++ < 50) {
            // keep going
        }
    }

    @Nested
    @DisplayName("Scenario: Duplicate event does not create a second notification")
    class DuplicateEvent {
        final UUID orderId = UUID.randomUUID();

        @Test
        void givenProcessedEvent_whenKafkaRedeliversIt_thenExactlyOneNotificationAndOneDelivery() {
            // Given an OrderCreated event E1 for customer buyer@acme.example.test, already processed
            var e1 = orderCreated(orderId, UUID.randomUUID(), "buyer@acme.example.test");
            deliver(e1, Topics.ORDER_EVENTS);
            long duplicatesBefore = metrics.duplicatesTotal();

            // When Kafka delivers event E1 again
            deliver(e1, Topics.ORDER_EVENTS);
            runDeliveryUntilIdle();

            // Then exactly one ORDER_CREATED notification exists, the duplicate is counted, one delivery attempt
            assertThat(of(orderId)).hasSize(1);
            assertThat(of(orderId).get(0).getTemplate()).isEqualTo("ORDER_CREATED");
            assertThat(metrics.duplicatesTotal()).isEqualTo(duplicatesBefore + 1);
            assertThat(of(orderId).get(0).getAttempts()).isEqualTo(1);
            assertThat(service.attemptsOf(of(orderId).get(0).getId())).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Scenario: Replay after the processed-event marker was purged is still protected")
    class ReplayAfterPurge {
        final UUID orderId = UUID.randomUUID();

        @Test
        void givenPurgedProcessedMarker_whenEventIsReplayed_thenStillOneNotification() {
            var e1 = orderCreated(orderId, UUID.randomUUID(), "buyer@acme.example.test");
            deliver(e1, Topics.ORDER_EVENTS);
            assertThat(of(orderId)).hasSize(1);
            processed.deleteById(new ProcessedEvent.Key("notification-service", e1.eventId()));

            deliver(e1, Topics.ORDER_EVENTS);

            assertThat(of(orderId)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Scenario: A flaky provider is retried with backoff until the notification is sent")
    class FlakyProvider {
        final UUID orderId = UUID.randomUUID();

        @Test
        void givenFailingDomain_whenSchedulerRuns_thenSentAfterThreeAttempts() {
            deliver(orderCreated(orderId, UUID.randomUUID(), "ops@fail.example.test"), Topics.ORDER_EVENTS);
            assertThat(of(orderId).get(0).getStatus()).isEqualTo(NotificationStatus.PENDING);

            runDeliveryUntilIdle();

            Notification n = of(orderId).get(0);
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
            assertThat(n.getAttempts()).isEqualTo(3);
            assertThat(service.attemptsOf(n.getId())).extracting(NotificationAttempt::getOutcome)
                    .containsExactly("FAILURE", "FAILURE", "SUCCESS");
        }
    }

    @Nested
    @DisplayName("Scenario: A fulfillment event is notified using the contact learned from OrderCreated")
    class FulfillmentUsesContact {
        final UUID orderId = UUID.randomUUID();
        final UUID customerId = UUID.randomUUID();

        @Test
        void givenKnownOrderContact_whenFulfillmentCompletedArrives_thenNotificationForSameCustomer() {
            deliver(orderCreated(orderId, customerId, "buyer@acme.example.test"), Topics.ORDER_EVENTS);

            var completed = EventEnvelope.forOrder(EventTypes.FULFILLMENT_COMPLETED, orderId, "fulfillment-service",
                    "corr-bdd", null, new EventPayloads.FulfillmentCompleted(orderId, UUID.randomUUID(), "OMS-1234"));
            deliver(completed, Topics.FULFILLMENT_EVENTS);

            Notification n = of(orderId).stream().filter(x -> x.getTemplate().equals("FULFILLMENT_COMPLETED"))
                    .findFirst().orElseThrow();
            assertThat(n.getCustomerId()).isEqualTo(customerId);
            assertThat(n.getSubject()).contains("OMS-1234");
        }
    }
}

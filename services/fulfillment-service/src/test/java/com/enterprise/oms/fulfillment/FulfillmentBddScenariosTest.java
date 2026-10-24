package com.enterprise.oms.fulfillment;

import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventJson;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.common.outbox.OutboxEvent;
import com.enterprise.oms.common.outbox.OutboxEventRepository;
import com.enterprise.oms.fulfillment.config.FulfillmentProperties;
import com.enterprise.oms.fulfillment.domain.Fulfillment;
import com.enterprise.oms.fulfillment.domain.FulfillmentRepository;
import com.enterprise.oms.fulfillment.domain.FulfillmentStatus;
import com.enterprise.oms.fulfillment.service.FulfillmentProgressJob;
import com.enterprise.oms.fulfillment.service.FulfillmentService;
import com.enterprise.oms.fulfillment.service.TrackingNumbers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executable mirror of tests/bdd/fulfillment-scenarios.feature (Given/When/Then as nested classes, no Kafka needed:
 * the command handler is called as the Kafka listener would, the scheduler is driven by hand so the test is
 * deterministic).
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Feature: Fulfillment lifecycle and failure handling")
class FulfillmentBddScenariosTest {

    @Autowired FulfillmentService service;
    @Autowired FulfillmentProgressJob job;
    @Autowired FulfillmentRepository repository;
    @Autowired OutboxEventRepository outbox;
    @Autowired AuditService audit;
    @Autowired FulfillmentProperties props;

    @BeforeEach
    void background() {
        // Background: the service allows 3 internal attempts; no random failures, scheduler driven by the test
        props.setMaxAttempts(3);
        props.setFailureRate(0.0);
        props.setSchedulerEnabled(false);
    }

    private EventEnvelope<EventPayloads.StartFulfillment> startCommand(UUID orderId, String city) {
        return EventEnvelope.forOrder(EventTypes.START_FULFILLMENT, orderId, "order-service", "corr-bdd", null,
                new EventPayloads.StartFulfillment(orderId, new EventPayloads.Address("1 Main St", city, "1", "US"),
                        List.of(new EventPayloads.SkuItem(UUID.randomUUID(), "SKU-1001", 1))));
    }

    private void runSchedulerUntilIdle() {
        int guard = 0;
        while (job.runOnce() > 0 && guard++ < 50) {
            // keep going
        }
    }

    private List<String> outboxTypesFor(UUID orderId) {
        return outbox.findAll().stream().filter(o -> o.getAggregateId().equals(orderId.toString()))
                .sorted(java.util.Comparator.comparing(OutboxEvent::getCreatedAt))
                .map(OutboxEvent::getEventType).toList();
    }

    private JsonNode outboxPayload(UUID orderId, String type) {
        return outbox.findAll().stream()
                .filter(o -> o.getAggregateId().equals(orderId.toString()) && o.getEventType().equals(type))
                .findFirst().map(o -> EventJson.parseEnvelope(o.getPayload()).payload()).orElseThrow();
    }

    @Nested
    @DisplayName("Scenario: Failed fulfillment is retried internally and reported once after the last attempt")
    class FailedFulfillment {

        final UUID orderId = UUID.randomUUID();

        @Test
        void givenAFailtownOrder_whenCommandReceivedAndSchedulerRuns_thenFailedAfterThreeAttemptsWithOneFailedEvent() {
            // Given a StartFulfillment command for an order shipping to city "FAILTOWN"
            var command = startCommand(orderId, "FAILTOWN");

            // When the command is received and the scheduler runs until it has nothing left to do
            service.start(command, command.payload());
            runSchedulerUntilIdle();

            // Then a fulfillment exists for the order with status FAILED and 3 attempts
            Fulfillment f = repository.findByOrderId(orderId).orElseThrow();
            assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.FAILED);
            assertThat(f.getAttempts()).isEqualTo(3);
            // And its failure reason mentions FAILTOWN
            assertThat(f.getFailureReason()).contains("FAILTOWN");
            // And exactly one FulfillmentStarted and exactly one FulfillmentFailed (attempts 3), no Completed
            assertThat(outboxTypesFor(orderId)).containsExactly(EventTypes.FULFILLMENT_STARTED,
                    EventTypes.FULFILLMENT_FAILED);
            assertThat(outboxPayload(orderId, EventTypes.FULFILLMENT_FAILED).get("attempts").asInt()).isEqualTo(3);
            assertThat(outboxTypesFor(orderId)).doesNotContain(EventTypes.FULFILLMENT_COMPLETED);
            // And the failure is recorded in the audit trail
            assertThat(audit.search("Fulfillment", f.getId().toString(), PageRequest.of(0, 20)).content())
                    .extracting(a -> a.action()).contains("ATTEMPT_FAILED", "FAILED");
        }
    }

    @Nested
    @DisplayName("Scenario: An operator retry of a failed fulfillment once the cause is fixed completes it")
    class OperatorRetry {

        final UUID orderId = UUID.randomUUID();

        @Test
        void givenAFailedFulfillmentWhoseCauseIsFixed_whenOperatorRetries_thenItCompletesWithTracking() {
            // Given a failed fulfillment (random failures on)
            props.setFailureRate(1.0);
            var command = startCommand(orderId, "Springfield");
            service.start(command, command.payload());
            runSchedulerUntilIdle();
            Fulfillment failed = repository.findByOrderId(orderId).orElseThrow();
            assertThat(failed.getStatus()).isEqualTo(FulfillmentStatus.FAILED);
            // And the simulated failure cause has been fixed
            props.setFailureRate(0.0);

            // When an operator retries the fulfillment and the scheduler runs
            service.retry(failed.getId());
            runSchedulerUntilIdle();

            // Then COMPLETED with a deterministic tracking number, and the event carries it
            Fulfillment done = repository.findByOrderId(orderId).orElseThrow();
            assertThat(done.getStatus()).isEqualTo(FulfillmentStatus.COMPLETED);
            assertThat(done.getTrackingNumber()).isEqualTo(TrackingNumbers.forOrder(orderId));
            assertThat(outboxPayload(orderId, EventTypes.FULFILLMENT_COMPLETED).get("trackingNumber").asText())
                    .isEqualTo(done.getTrackingNumber());
        }
    }

    @Nested
    @DisplayName("Scenario: Duplicate StartFulfillment command is a no-op")
    class DuplicateCommand {

        final UUID orderId = UUID.randomUUID();

        @Test
        void givenAnExistingFulfillment_whenASecondCommandArrives_thenNothingChanges() {
            // Given a fulfillment already exists for an order
            var first = startCommand(orderId, "Springfield");
            service.start(first, first.payload());
            long startedBefore = outboxTypesFor(orderId).stream()
                    .filter(EventTypes.FULFILLMENT_STARTED::equals).count();

            // When a second StartFulfillment command arrives for the same order
            var second = startCommand(orderId, "Springfield");
            assertThat(service.start(second, second.payload())).isEmpty();

            // Then no second fulfillment is created and no additional FulfillmentStarted is written
            assertThat(repository.findAll().stream().filter(f -> f.getOrderId().equals(orderId))).hasSize(1);
            assertThat(startedBefore).isEqualTo(1);
            assertThat(outboxTypesFor(orderId)).containsExactly(EventTypes.FULFILLMENT_STARTED);
        }
    }
}

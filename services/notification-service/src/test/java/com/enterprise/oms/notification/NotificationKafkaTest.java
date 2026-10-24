package com.enterprise.oms.notification;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventJson;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.common.events.Topics;
import com.enterprise.oms.common.idempotency.ProcessedEvent;
import com.enterprise.oms.common.idempotency.ProcessedEventRepository;
import com.enterprise.oms.common.messaging.MessagingMetrics;
import com.enterprise.oms.common.observability.LogMasking;
import com.enterprise.oms.notification.config.NotificationProperties;
import com.enterprise.oms.notification.domain.Notification;
import com.enterprise.oms.notification.domain.NotificationAttempt;
import com.enterprise.oms.notification.domain.NotificationRepository;
import com.enterprise.oms.notification.domain.NotificationStatus;
import com.enterprise.oms.notification.sender.NotificationMessage;
import com.enterprise.oms.notification.sender.NotificationSender;
import com.enterprise.oms.notification.sender.SendResult;
import com.enterprise.oms.notification.sender.SimulatedSender;
import com.enterprise.oms.notification.service.NotificationService;
import com.enterprise.oms.testsupport.JwtTestTokens;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Notification service end to end on embedded KRaft Kafka + H2: all nine event types, duplicates, replay, delivery
 * retry/backoff to SENT and to FAILED, operator retry, poison messages to the DLT, correlation id propagation and
 * e-mail masking in logs.
 */
@SpringBootTest(properties = {"oms.notification.scheduler-enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(NotificationKafkaTest.RecordingConfig.class)
@EmbeddedKafka(partitions = 3, kraft = true, bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {Topics.ORDER_EVENTS, Topics.ORDER_EVENTS + ".DLT", Topics.FULFILLMENT_EVENTS,
                Topics.FULFILLMENT_EVENTS + ".DLT"})
class NotificationKafkaTest {

    record SendCall(UUID notificationId, int attemptNumber, String mdcCorrelationId) {
    }

    /** Wraps the simulated sender and records what the delivery thread saw (attempt number, MDC correlation id). */
    static class RecordingSender implements NotificationSender {
        final List<SendCall> calls = new CopyOnWriteArrayList<>();
        private final SimulatedSender delegate;

        RecordingSender(NotificationProperties props) {
            this.delegate = new SimulatedSender(props);
        }

        @Override
        public SendResult send(NotificationMessage m) {
            calls.add(new SendCall(m.notificationId(), m.attemptNumber(), MDC.get("correlationId")));
            return delegate.send(m);
        }
    }

    @TestConfiguration
    static class RecordingConfig {
        @Bean
        @Primary
        RecordingSender recordingSender(NotificationProperties props) {
            return new RecordingSender(props);
        }
    }

    @DynamicPropertySource
    static void jwt(DynamicPropertyRegistry registry) {
        JwtTestTokens.registerProperties(registry);
    }

    @Autowired NotificationRepository repository;
    @Autowired NotificationService service;
    @Autowired NotificationProperties props;
    @Autowired RecordingSender sender;
    @Autowired MessagingMetrics metrics;
    @Autowired MeterRegistry registry;
    @Autowired ProcessedEventRepository processed;
    @Autowired MockMvc mvc;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired @Qualifier("omsKafkaTemplate") KafkaTemplate<String, String> kafka;

    private Consumer<String, String> orderDlt;
    private Consumer<String, String> fulfillmentDlt;
    private final List<ConsumerRecord<String, String>> seenDlt = new ArrayList<>();
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        props.setMaxAttempts(5);
        props.setSimulatedFailures(2);
        orderDlt = consumer(Topics.ORDER_EVENTS + ".DLT");
        fulfillmentDlt = consumer(Topics.FULFILLMENT_EVENTS + ".DLT");
        logs = new ListAppender<>();
        logs.start();
        Logger logger = (Logger) LoggerFactory.getLogger("com.enterprise.oms.notification");
        logger.setLevel(Level.INFO);
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger("com.enterprise.oms.notification")).detachAppender(logs);
        orderDlt.close();
        fulfillmentDlt.close();
        props.setMaxAttempts(5);
        props.setSimulatedFailures(2);
    }

    private Consumer<String, String> consumer(String topic) {
        Map<String, Object> p = KafkaTestUtils.consumerProps("notif-it-" + UUID.randomUUID(), "true", broker);
        Consumer<String, String> c = new DefaultKafkaConsumerFactory<>(p, new StringDeserializer(),
                new StringDeserializer()).createConsumer();
        c.subscribe(List.of(topic));
        return c;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static String email(String domain) {
        return "buyer" + UUID.randomUUID().toString().substring(0, 8) + "@" + domain;
    }

    private EventEnvelope<Object> orderEvent(String type, UUID orderId, String correlationId, Object payload) {
        return EventEnvelope.forOrder(type, orderId, "order-service", correlationId, null, payload);
    }

    private void sendOrder(EventEnvelope<?> env) throws Exception {
        kafka.send(new ProducerRecord<>(Topics.ORDER_EVENTS, env.aggregateId(), EventJson.toJson(env))).get();
    }

    private void sendFulfillment(EventEnvelope<?> env) throws Exception {
        kafka.send(new ProducerRecord<>(Topics.FULFILLMENT_EVENTS, env.aggregateId(), EventJson.toJson(env))).get();
    }

    private EventEnvelope<Object> created(UUID orderId, UUID customerId, String email, String corr) {
        return orderEvent(EventTypes.ORDER_CREATED, orderId, corr, new EventPayloads.OrderCreated(orderId, customerId,
                email, new BigDecimal("12.50"), "USD", List.of(), "Springfield"));
    }

    private List<Notification> notificationsOf(UUID orderId) {
        return repository.findAll().stream().filter(n -> n.getOrderId().equals(orderId)).toList();
    }

    private void awaitCount(UUID orderId, int count) {
        await().atMost(Duration.ofSeconds(40)).until(() -> notificationsOf(orderId).size() >= count);
    }

    private Notification awaitStatus(UUID orderId, String template, NotificationStatus status) {
        await().atMost(Duration.ofSeconds(40)).until(() -> notificationsOf(orderId).stream()
                .anyMatch(n -> n.getTemplate().equals(template) && n.getStatus() == status));
        return notificationsOf(orderId).stream().filter(n -> n.getTemplate().equals(template)).findFirst().orElseThrow();
    }

    private ConsumerRecord<String, String> awaitDlt(Consumer<String, String> dlt, String needle) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        await().atMost(Duration.ofSeconds(40)).until(() -> {
            dlt.poll(Duration.ofMillis(200)).forEach(seenDlt::add);
            found.clear();
            seenDlt.stream().filter(r -> r.value() != null && r.value().contains(needle)).forEach(found::add);
            return !found.isEmpty();
        });
        return found.get(0);
    }

    private static String header(ConsumerRecord<String, String> rec, String name) {
        Header h = rec.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private double counter(String name) {
        return registry.find(name).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    // ---- tests ------------------------------------------------------------------------------------------------

    @Test
    void everyOrderAndFulfillmentEventTypeProducesItsNotificationAndIsDelivered() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        String email = email("acme.example.test");
        sendOrder(created(orderId, customerId, email, "corr-all-1"));
        awaitCount(orderId, 1); // order_contacts now knows the recipient for the fulfillment events below

        sendOrder(orderEvent(EventTypes.ORDER_CONFIRMED, orderId, "corr-all-2",
                new EventPayloads.OrderConfirmed(orderId, customerId, email, new BigDecimal("12.50"))));
        sendOrder(orderEvent(EventTypes.ORDER_REJECTED, orderId, "corr-all-3",
                new EventPayloads.OrderRejected(orderId, customerId, email, "insufficient stock")));
        sendOrder(orderEvent(EventTypes.ORDER_CANCELLED, orderId, "corr-all-4",
                new EventPayloads.OrderCancelled(orderId, customerId, email, "changed my mind")));
        sendOrder(orderEvent(EventTypes.ORDER_FULFILLED, orderId, "corr-all-5",
                new EventPayloads.OrderFulfilled(orderId, customerId, email, "OMS-AAAA")));
        sendOrder(orderEvent(EventTypes.ORDER_FAILED, orderId, "corr-all-6",
                new EventPayloads.OrderFailed(orderId, customerId, email, "fulfillment failed")));
        UUID fulfillmentId = UUID.randomUUID();
        sendFulfillment(orderEvent(EventTypes.FULFILLMENT_STARTED, orderId, "corr-all-7",
                new EventPayloads.FulfillmentStarted(orderId, fulfillmentId)));
        sendFulfillment(orderEvent(EventTypes.FULFILLMENT_COMPLETED, orderId, "corr-all-8",
                new EventPayloads.FulfillmentCompleted(orderId, fulfillmentId, "OMS-BBBB")));
        sendFulfillment(orderEvent(EventTypes.FULFILLMENT_FAILED, orderId, "corr-all-9",
                new EventPayloads.FulfillmentFailed(orderId, fulfillmentId, "no stock at dock", 3)));

        awaitCount(orderId, 9);
        await().atMost(Duration.ofSeconds(40)).until(() -> notificationsOf(orderId).stream()
                .allMatch(n -> n.getStatus() == NotificationStatus.SENT));

        List<Notification> all = notificationsOf(orderId);
        assertThat(all.stream().map(Notification::getTemplate).collect(Collectors.toSet())).containsExactlyInAnyOrder(
                "ORDER_CREATED", "ORDER_CONFIRMED", "ORDER_REJECTED", "ORDER_CANCELLED", "ORDER_FULFILLED",
                "ORDER_FAILED", "FULFILLMENT_STARTED", "FULFILLMENT_COMPLETED", "FULFILLMENT_FAILED");
        assertThat(all).allSatisfy(n -> {
            assertThat(n.getCustomerId()).isEqualTo(customerId);
            assertThat(n.getChannel().name()).isEqualTo("EMAIL");
            assertThat(n.getSubject()).isNotBlank().doesNotContain("@");
            assertThat(n.getAttempts()).isEqualTo(1);
            assertThat(n.getSentAt()).isNotNull();
            assertThat(n.getSourceEventId()).isNotNull();
        });
        // correlation id of each event travelled onto its notification
        assertThat(all.stream().map(Notification::getCorrelationId).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("corr-all-1", "corr-all-2", "corr-all-3", "corr-all-4", "corr-all-5",
                        "corr-all-6", "corr-all-7", "corr-all-8", "corr-all-9");
        // fulfillment notifications were resolved through order_contacts (they carry only an orderId)
        assertThat(all.stream().filter(n -> n.getTemplate().startsWith("FULFILLMENT_")).toList()).hasSize(3);
        assertThat(all.stream().filter(n -> n.getTemplate().equals("FULFILLMENT_COMPLETED")).findFirst().orElseThrow()
                .getSubject()).contains("OMS-BBBB");
    }

    @Test
    void duplicateDeliveryOfTheSameEventIdCreatesExactlyOneNotification() throws Exception {
        UUID orderId = UUID.randomUUID();
        var env = created(orderId, UUID.randomUUID(), email("acme.example.test"), "corr-dup");
        long dupBefore = metrics.duplicatesTotal();

        sendOrder(env);
        sendOrder(env);
        sendOrder(env);

        await().atMost(Duration.ofSeconds(40)).until(() -> metrics.duplicatesTotal() >= dupBefore + 2);
        awaitStatus(orderId, "ORDER_CREATED", NotificationStatus.SENT);
        assertThat(notificationsOf(orderId)).hasSize(1);
        assertThat(sender.calls.stream().filter(c -> c.notificationId().equals(notificationsOf(orderId).get(0).getId())))
                .hasSize(1);
    }

    @Test
    void replayAfterProcessedMarkersAreGoneIsStillProtectedByUniqueSourceEventAndTemplate() throws Exception {
        UUID orderId = UUID.randomUUID();
        var env = created(orderId, UUID.randomUUID(), email("acme.example.test"), "corr-replay");
        sendOrder(env);
        awaitStatus(orderId, "ORDER_CREATED", NotificationStatus.SENT);
        double before = counter("oms.notification.duplicates");

        // simulate retention clean-up / offset reset: forget the processed marker and replay the same event
        processed.deleteById(new ProcessedEvent.Key("notification-service", env.eventId()));
        sendOrder(env);

        await().atMost(Duration.ofSeconds(40)).until(() -> counter("oms.notification.duplicates") >= before + 1);
        assertThat(notificationsOf(orderId)).hasSize(1);
        assertThat(notificationsOf(orderId).get(0).getAttempts()).isEqualTo(1);
    }

    @Test
    void failingRecipientIsRetriedWithBackoffAndReachesSentAfterTheSimulatedFailures() throws Exception {
        UUID orderId = UUID.randomUUID();
        var env = created(orderId, UUID.randomUUID(), email("fail.example.test"), "corr-retry-flow");
        sendOrder(env);

        Notification sent = awaitStatus(orderId, "ORDER_CREATED", NotificationStatus.SENT);

        assertThat(sent.getAttempts()).isEqualTo(3); // default simulated-failures = 2
        assertThat(sent.getLastError()).isNull();
        List<NotificationAttempt> attempts = service.attemptsOf(sent.getId());
        assertThat(attempts).extracting(NotificationAttempt::getOutcome).containsExactly("FAILURE", "FAILURE", "SUCCESS");
        assertThat(attempts).extracting(NotificationAttempt::getAttemptNo).containsExactly(1, 2, 3);
        assertThat(attempts.get(0).getError()).doesNotContain("@");
        // backoff: 10 ms then 20 ms between attempts
        assertThat(Duration.between(attempts.get(0).getAttemptedAt(), attempts.get(1).getAttemptedAt()))
                .isGreaterThanOrEqualTo(Duration.ofMillis(10));
        assertThat(Duration.between(attempts.get(1).getAttemptedAt(), attempts.get(2).getAttemptedAt()))
                .isGreaterThanOrEqualTo(Duration.ofMillis(20));
        // the delivery thread ran with the event's correlation id in MDC and attempt numbers 1..3
        List<SendCall> calls = sender.calls.stream().filter(c -> c.notificationId().equals(sent.getId())).toList();
        assertThat(calls).extracting(SendCall::attemptNumber).containsExactly(1, 2, 3);
        assertThat(calls).extracting(SendCall::mdcCorrelationId).containsOnly("corr-retry-flow");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void failureAfterMaxAttemptsThenOperatorRetrySucceeds() throws Exception {
        props.setMaxAttempts(3);
        props.setSimulatedFailures(100);
        UUID orderId = UUID.randomUUID();
        sendOrder(created(orderId, UUID.randomUUID(), email("fail.example.test"), "corr-fail-max"));

        Notification failed = awaitStatus(orderId, "ORDER_CREATED", NotificationStatus.FAILED);

        assertThat(failed.getAttempts()).isEqualTo(3);
        assertThat(failed.getLastError()).contains("Simulated").doesNotContain("@");
        assertThat(failed.getNextAttemptAt()).isNull();
        assertThat(service.attemptsOf(failed.getId())).hasSize(3)
                .extracting(NotificationAttempt::getOutcome).containsOnly("FAILURE");
        int callsBefore = sender.calls.size();
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until(() -> sender.calls.size() == callsBefore);

        // the provider recovers; an operator retries the FAILED notification (fresh budget: fails twice, 3rd succeeds)
        props.setSimulatedFailures(2);
        mvc.perform(post("/api/v1/admin/notifications/" + failed.getId() + "/retry")
                        .header(HttpHeaders.AUTHORIZATION, JwtTestTokens.operator()))
                .andExpect(status().isOk());

        Notification sent = awaitStatus(orderId, "ORDER_CREATED", NotificationStatus.SENT);
        assertThat(sent.getAttempts()).isEqualTo(3);
        assertThat(service.attemptsOf(sent.getId())).hasSize(6);
    }

    @Test
    void poisonEventsGoToTheDltWithoutCreatingNotifications() throws Exception {
        // 1) invalid e-mail address in an order event: IllegalArgumentException, non-retryable
        UUID badEmailOrder = UUID.randomUUID();
        var badEmail = created(badEmailOrder, UUID.randomUUID(), "not-an-email", "corr-poison-1");
        sendOrder(badEmail);
        ConsumerRecord<String, String> dlt1 = awaitDlt(orderDlt, badEmail.eventId().toString());
        assertThat(dlt1.topic()).isEqualTo("oms.order.events.v1.DLT");
        assertThat(header(dlt1, "x-exception-class")).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(header(dlt1, "x-original-topic")).isEqualTo(Topics.ORDER_EVENTS);
        assertThat(notificationsOf(badEmailOrder)).isEmpty();

        // 2) not JSON at all
        String garbage = "<<garbage " + UUID.randomUUID();
        kafka.send(new ProducerRecord<>(Topics.ORDER_EVENTS, UUID.randomUUID().toString(), garbage)).get();
        assertThat(header(awaitDlt(orderDlt, garbage), "x-exception-class")).contains("MalformedEventException");

        // 3) payload that cannot be bound (customerId is not a UUID)
        UUID unbindable = UUID.randomUUID();
        String json = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"OrderCreated\",\"schemaVersion\":1,"
                + "\"occurredAt\":\"" + Instant.now() + "\",\"correlationId\":\"corr-poison-3\",\"aggregateType\":\"Order\","
                + "\"aggregateId\":\"" + unbindable + "\",\"producer\":\"order-service\","
                + "\"payload\":{\"orderId\":\"" + unbindable + "\",\"customerId\":\"nope\",\"customerEmail\":\"a@b.example.test\"}}";
        kafka.send(new ProducerRecord<>(Topics.ORDER_EVENTS, unbindable.toString(), json)).get();
        awaitDlt(orderDlt, "\"customerId\":\"nope\"");
        assertThat(notificationsOf(unbindable)).isEmpty();

        // 4) fulfillment event for an order whose contact never arrives: retried with backoff, then dead-lettered
        UUID unknownOrder = UUID.randomUUID();
        long retriesBefore = metrics.retriesTotal();
        var orphan = orderEvent(EventTypes.FULFILLMENT_STARTED, unknownOrder, "corr-poison-4",
                new EventPayloads.FulfillmentStarted(unknownOrder, UUID.randomUUID()));
        sendFulfillment(orphan);
        ConsumerRecord<String, String> dlt4 = awaitDlt(fulfillmentDlt, orphan.eventId().toString());
        assertThat(header(dlt4, "x-exception-class")).endsWith("ContactUnavailableException");
        assertThat(metrics.retriesTotal() - retriesBefore).isGreaterThanOrEqualTo(3);
        assertThat(notificationsOf(unknownOrder)).isEmpty();

        // the consumer keeps working afterwards
        UUID healthy = UUID.randomUUID();
        sendOrder(created(healthy, UUID.randomUUID(), email("acme.example.test"), "corr-after-poison"));
        awaitStatus(healthy, "ORDER_CREATED", NotificationStatus.SENT);
    }

    @Test
    void fulfillmentEventArrivingBeforeOrderCreatedIsRetriedUntilTheContactIsKnown() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        var started = orderEvent(EventTypes.FULFILLMENT_STARTED, orderId, "corr-early",
                new EventPayloads.FulfillmentStarted(orderId, UUID.randomUUID()));
        sendFulfillment(started);                 // contact unknown: container retries with backoff
        Thread.sleep(30);
        sendOrder(created(orderId, customerId, email("acme.example.test"), "corr-early-created"));

        awaitStatus(orderId, "FULFILLMENT_STARTED", NotificationStatus.SENT);
        assertThat(notificationsOf(orderId)).hasSize(2);
        assertThat(notificationsOf(orderId)).allMatch(n -> n.getCustomerId().equals(customerId));
    }

    @Test
    void unknownEventTypesAreIgnoredAndCountedNotDeadLettered() throws Exception {
        UUID orderId = UUID.randomUUID();
        var env = orderEvent("InventoryReserved", orderId, "corr-ignored", Map.of("orderId", orderId.toString()));
        long ignoredBefore = metrics.ignoredTotal();
        long dltBefore = metrics.deadLetteredTotal();

        sendOrder(env);

        await().atMost(Duration.ofSeconds(40)).until(() -> metrics.ignoredTotal() == ignoredBefore + 1);
        assertThat(metrics.deadLetteredTotal()).isEqualTo(dltBefore);
        assertThat(notificationsOf(orderId)).isEmpty();
    }

    @Test
    void recipientAddressesAreOnlyEverLoggedMasked() throws Exception {
        UUID orderId = UUID.randomUUID();
        String email = email("fail.example.test");
        sendOrder(created(orderId, UUID.randomUUID(), email, "corr-masking"));
        awaitStatus(orderId, "ORDER_CREATED", NotificationStatus.SENT);

        List<String> messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        String localPart = email.substring(0, email.indexOf('@'));
        assertThat(messages).isNotEmpty();
        assertThat(messages).noneMatch(m -> m.contains(email) || m.contains(localPart));
        assertThat(messages).anyMatch(m -> m.contains(LogMasking.maskEmail(email)));
        Set<String> loggers = logs.list.stream().map(ILoggingEvent::getLoggerName).collect(Collectors.toSet());
        assertThat(loggers).anyMatch(l -> l.endsWith("SimulatedSender"));
        assertThat(loggers).anyMatch(l -> l.endsWith("NotificationService"));
    }
}

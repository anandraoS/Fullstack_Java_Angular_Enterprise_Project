package com.enterprise.oms.notification;

import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.common.error.InvalidStateTransitionException;
import com.enterprise.oms.common.error.NotFoundException;
import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.notification.config.NotificationProperties;
import com.enterprise.oms.notification.domain.Notification;
import com.enterprise.oms.notification.domain.NotificationAttempt;
import com.enterprise.oms.notification.domain.NotificationAttemptRepository;
import com.enterprise.oms.notification.domain.NotificationChannel;
import com.enterprise.oms.notification.domain.NotificationRepository;
import com.enterprise.oms.notification.domain.NotificationStatus;
import com.enterprise.oms.notification.domain.NotificationTemplate;
import com.enterprise.oms.notification.domain.OrderContact;
import com.enterprise.oms.notification.domain.OrderContactRepository;
import com.enterprise.oms.notification.sender.NotificationMessage;
import com.enterprise.oms.notification.sender.NotificationSender;
import com.enterprise.oms.notification.sender.SendResult;
import com.enterprise.oms.notification.service.BackoffPolicy;
import com.enterprise.oms.notification.service.ContactUnavailableException;
import com.enterprise.oms.notification.service.NotificationDraft;
import com.enterprise.oms.notification.service.NotificationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationRepository notifications;
    @Mock NotificationAttemptRepository attempts;
    @Mock OrderContactRepository contacts;
    @Mock NotificationSender sender;
    @Mock AuditService audit;

    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private NotificationProperties props;
    private NotificationService service;

    private final UUID orderId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private static final String EMAIL = "buyer@acme.example.test";

    @BeforeEach
    void setUp() {
        props = new NotificationProperties();
        props.setMaxAttempts(3);
        props.setBackoffInitialMs(100);
        props.setBackoffMultiplier(2.0);
        props.setBackoffMaxMs(1000);
        service = new NotificationService(notifications, attempts, contacts, sender, new BackoffPolicy(props), props,
                audit, new SimpleMeterRegistry(), Clock.fixed(now, ZoneOffset.UTC));
    }

    private EventEnvelope<?> envelope(String type) {
        return EventEnvelope.forOrder(type, orderId, "order-service", "corr-n-1", null, null);
    }

    private NotificationDraft created(String email) {
        return NotificationDraft.of(new EventPayloads.OrderCreated(orderId, customerId, email, BigDecimal.TEN, "USD",
                List.of(), "Springfield"));
    }

    private Notification pending() {
        return new Notification(UUID.randomUUID(), customerId, orderId, NotificationChannel.EMAIL,
                NotificationTemplate.ORDER_CREATED, "subject", "corr-n-1", UUID.randomUUID(), now);
    }

    // ---- ingest -----------------------------------------------------------------------------------------------

    @Test
    void orderEventStoresContactAndCreatesPendingNotificationWithCorrelationAndSource() {
        when(contacts.findById(orderId)).thenReturn(Optional.empty());
        when(contacts.save(any(OrderContact.class))).thenAnswer(i -> i.getArgument(0));
        var env = envelope(EventTypes.ORDER_CREATED);

        Optional<Notification> created = service.ingest(env, created(EMAIL));

        assertThat(created).isPresent();
        Notification n = created.get();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(n.getTemplate()).isEqualTo("ORDER_CREATED");
        assertThat(n.getCustomerId()).isEqualTo(customerId);
        assertThat(n.getSourceEventId()).isEqualTo(env.eventId());
        assertThat(n.getCorrelationId()).isEqualTo("corr-n-1");
        assertThat(n.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        ArgumentCaptor<OrderContact> contact = ArgumentCaptor.forClass(OrderContact.class);
        verify(contacts).save(contact.capture());
        assertThat(contact.getValue().getCustomerEmail()).isEqualTo(EMAIL);
        verify(notifications).save(n);
    }

    @Test
    void duplicateSourceEventAndTemplateIsSkipped() {
        when(contacts.findById(orderId)).thenReturn(Optional.of(new OrderContact(orderId, customerId, EMAIL, now)));
        var env = envelope(EventTypes.ORDER_CREATED);
        when(notifications.existsBySourceEventIdAndTemplate(env.eventId(), "ORDER_CREATED")).thenReturn(true);

        assertThat(service.ingest(env, created(EMAIL))).isEmpty();

        verify(notifications, never()).save(any());
    }

    @Test
    void fulfillmentEventResolvesCustomerFromOrderContacts() {
        when(contacts.findById(orderId)).thenReturn(Optional.of(new OrderContact(orderId, customerId, EMAIL, now)));
        var env = envelope(EventTypes.FULFILLMENT_STARTED);

        Notification n = service.ingest(env,
                NotificationDraft.of(new EventPayloads.FulfillmentStarted(orderId, UUID.randomUUID()))).orElseThrow();

        assertThat(n.getCustomerId()).isEqualTo(customerId);
        assertThat(n.getTemplate()).isEqualTo("FULFILLMENT_STARTED");
        verify(contacts, never()).save(any());
    }

    @Test
    void fulfillmentEventWithoutKnownContactIsRetryableNotInvalid() {
        when(contacts.findById(orderId)).thenReturn(Optional.empty());
        var env = envelope(EventTypes.FULFILLMENT_COMPLETED);
        var draft = NotificationDraft.of(new EventPayloads.FulfillmentCompleted(orderId, UUID.randomUUID(), "T"));

        assertThatThrownBy(() -> service.ingest(env, draft)).isInstanceOf(ContactUnavailableException.class)
                .isNotInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orderEventWithoutEmailOrWithInvalidEmailIsIllegalArgument() {
        when(contacts.findById(orderId)).thenReturn(Optional.empty());
        var env = envelope(EventTypes.ORDER_CREATED);
        assertThatThrownBy(() -> service.ingest(env, created(null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ingest(env, created("not-an-email")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ingest(env, created("a@b"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ingest(env, new NotificationDraft(null, customerId, EMAIL,
                NotificationTemplate.ORDER_CREATED, "s"))).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- delivery ---------------------------------------------------------------------------------------------

    @Test
    void successfulDeliveryMarksSentAndRecordsAttempt() {
        Notification n = pending();
        when(notifications.findById(n.getId())).thenReturn(Optional.of(n));
        when(contacts.findById(orderId)).thenReturn(Optional.of(new OrderContact(orderId, customerId, EMAIL, now)));
        when(sender.send(any())).thenReturn(SendResult.ok());

        assertThat(service.deliver(n.getId())).isTrue();

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getAttempts()).isEqualTo(1);
        ArgumentCaptor<NotificationMessage> msg = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(sender).send(msg.capture());
        assertThat(msg.getValue().recipient()).isEqualTo(EMAIL);
        assertThat(msg.getValue().attemptNumber()).isEqualTo(1);
        ArgumentCaptor<NotificationAttempt> attempt = ArgumentCaptor.forClass(NotificationAttempt.class);
        verify(attempts).save(attempt.capture());
        assertThat(attempt.getValue().getOutcome()).isEqualTo("SUCCESS");
    }

    @Test
    void failedAttemptSchedulesBackoffAndLastAttemptEndsInFailed() {
        Notification n = pending();
        when(notifications.findById(n.getId())).thenReturn(Optional.of(n));
        when(contacts.findById(orderId)).thenReturn(Optional.of(new OrderContact(orderId, customerId, EMAIL, now)));
        when(sender.send(any())).thenReturn(SendResult.failure("provider down for buyer@acme.example.test"));

        service.deliver(n.getId());
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(n.getAttempts()).isEqualTo(1);
        assertThat(n.getNextAttemptAt()).isEqualTo(now.plusMillis(100));
        assertThat(n.getLastError()).doesNotContain("buyer@acme.example.test");

        // second attempt is not due yet at the fixed clock
        assertThat(service.deliver(n.getId())).isFalse();
        assertThat(n.getAttempts()).isEqualTo(1);
    }

    @Test
    void exhaustingMaxAttemptsGivesFailed() {
        Notification n = pending();
        n.markAttemptFailed("e", false, now);
        n.markAttemptFailed("e", false, now);
        when(notifications.findById(n.getId())).thenReturn(Optional.of(n));
        when(contacts.findById(orderId)).thenReturn(Optional.of(new OrderContact(orderId, customerId, EMAIL, now)));
        when(sender.send(any())).thenReturn(SendResult.failure("nope"));

        service.deliver(n.getId());

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(n.getAttempts()).isEqualTo(3);
        assertThat(n.getNextAttemptAt()).isNull();
        verify(audit).record(eq("system"), eq("Notification"), eq(n.getId()), eq("FAILED"), any());
        assertThat(service.deliver(n.getId())).as("FAILED is not delivered again").isFalse();
    }

    @Test
    void senderExceptionsAreTreatedAsFailedAttemptsWithoutLeakingMessage() {
        Notification n = pending();
        when(notifications.findById(n.getId())).thenReturn(Optional.of(n));
        when(contacts.findById(orderId)).thenReturn(Optional.of(new OrderContact(orderId, customerId, EMAIL, now)));
        when(sender.send(any())).thenThrow(new IllegalStateException("smtp login for buyer@acme.example.test failed"));

        service.deliver(n.getId());

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(n.getLastError()).contains("IllegalStateException").doesNotContain("@");
    }

    @Test
    void missingContactCountsAsFailedAttempt() {
        Notification n = pending();
        when(notifications.findById(n.getId())).thenReturn(Optional.of(n));
        when(contacts.findById(orderId)).thenReturn(Optional.empty());

        service.deliver(n.getId());

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        verify(sender, never()).send(any());
    }

    // ---- admin retry ------------------------------------------------------------------------------------------

    @Test
    void retryRequeuesFailedOnly() {
        Notification failed = pending();
        failed.markAttemptFailed("x", true, null);
        when(notifications.findById(failed.getId())).thenReturn(Optional.of(failed));

        var view = service.retry(failed.getId());

        assertThat(view.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(failed.getAttempts()).isZero();
        verify(audit).record(eq("Notification"), eq(failed.getId()), eq("RETRY_REQUESTED"), any());

        Notification sent = pending();
        sent.markSent(now);
        when(notifications.findById(sent.getId())).thenReturn(Optional.of(sent));
        assertThatThrownBy(() -> service.retry(sent.getId())).isInstanceOf(InvalidStateTransitionException.class);

        UUID unknown = UUID.randomUUID();
        when(notifications.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.retry(unknown)).isInstanceOf(NotFoundException.class);
    }
}

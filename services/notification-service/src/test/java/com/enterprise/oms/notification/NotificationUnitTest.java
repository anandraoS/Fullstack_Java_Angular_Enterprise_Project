package com.enterprise.oms.notification;

import com.enterprise.oms.common.error.InvalidStateTransitionException;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.notification.config.NotificationProperties;
import com.enterprise.oms.notification.domain.Notification;
import com.enterprise.oms.notification.domain.NotificationChannel;
import com.enterprise.oms.notification.domain.NotificationStatus;
import com.enterprise.oms.notification.domain.NotificationTemplate;
import com.enterprise.oms.notification.sender.NotificationMessage;
import com.enterprise.oms.notification.sender.SendResult;
import com.enterprise.oms.notification.sender.SimulatedSender;
import com.enterprise.oms.notification.service.BackoffPolicy;
import com.enterprise.oms.notification.service.NotificationDraft;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit tests: status rules, template mapping, backoff, simulated sender, entity behaviour. */
class NotificationUnitTest {

    @Nested
    class StatusRules {
        private final Set<String> valid = Set.of("PENDING>SENT", "PENDING>RETRYING", "PENDING>FAILED",
                "RETRYING>SENT", "RETRYING>RETRYING", "RETRYING>FAILED", "FAILED>PENDING");

        @Test
        void everyOrderedPairIsClassifiedAsSpecified() {
            for (NotificationStatus from : NotificationStatus.values()) {
                for (NotificationStatus to : NotificationStatus.values()) {
                    assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to)
                            .isEqualTo(valid.contains(from + ">" + to));
                }
            }
        }

        @Test
        void invalidTransitionThrowsConflictException() {
            assertThatThrownBy(() -> NotificationStatus.SENT.assertTransitionTo(NotificationStatus.PENDING))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }

        @Test
        void onlyPendingAndRetryingAreDeliverable() {
            assertThat(NotificationStatus.PENDING.isDeliverable()).isTrue();
            assertThat(NotificationStatus.RETRYING.isDeliverable()).isTrue();
            assertThat(NotificationStatus.SENT.isDeliverable()).isFalse();
            assertThat(NotificationStatus.FAILED.isDeliverable()).isFalse();
        }
    }

    @Nested
    class TemplateMapping {
        private final UUID orderId = UUID.fromString("a1b2c3d4-0000-0000-0000-000000000001");
        private final UUID customerId = UUID.randomUUID();

        @ParameterizedTest
        @EnumSource(NotificationTemplate.class)
        void everyTemplateMapsBackFromItsEventType(NotificationTemplate t) {
            assertThat(NotificationTemplate.forEventType(t.eventType())).contains(t);
            assertThat(t.subject(orderId, "TRK-1")).contains("a1b2c3d4").doesNotContain("@");
        }

        @Test
        void unknownEventTypeHasNoTemplate() {
            assertThat(NotificationTemplate.forEventType("InventoryReserved")).isEmpty();
        }

        @Test
        void trackingNumberAppearsInShippingSubjectsAndDefaultsWhenMissing() {
            assertThat(NotificationTemplate.FULFILLMENT_COMPLETED.subject(orderId, "OMS-123"))
                    .isEqualTo("Order a1b2c3d4 has shipped - tracking OMS-123");
            assertThat(NotificationTemplate.ORDER_FULFILLED.subject(orderId, null))
                    .isEqualTo("Order a1b2c3d4 delivered - tracking pending");
        }

        @Test
        void orderEventDraftsCarryCustomerIdentity() {
            var d = NotificationDraft.of(new EventPayloads.OrderCreated(orderId, customerId, "a@x.example.test",
                    BigDecimal.TEN, "USD", List.of(), "Springfield"));
            assertThat(d.template()).isEqualTo(NotificationTemplate.ORDER_CREATED);
            assertThat(d.customerId()).isEqualTo(customerId);
            assertThat(d.customerEmail()).isEqualTo("a@x.example.test");
            assertThat(d.subject()).isEqualTo("Order a1b2c3d4 received");
            assertThat(NotificationDraft.of(new EventPayloads.OrderConfirmed(orderId, customerId, "a@x.example.test",
                    BigDecimal.TEN)).template()).isEqualTo(NotificationTemplate.ORDER_CONFIRMED);
            assertThat(NotificationDraft.of(new EventPayloads.OrderRejected(orderId, customerId, "a@x.example.test",
                    "r")).template()).isEqualTo(NotificationTemplate.ORDER_REJECTED);
            assertThat(NotificationDraft.of(new EventPayloads.OrderCancelled(orderId, customerId, "a@x.example.test",
                    "r")).template()).isEqualTo(NotificationTemplate.ORDER_CANCELLED);
            assertThat(NotificationDraft.of(new EventPayloads.OrderFulfilled(orderId, customerId, "a@x.example.test",
                    "TRK")).subject()).contains("TRK");
            assertThat(NotificationDraft.of(new EventPayloads.OrderFailed(orderId, customerId, "a@x.example.test",
                    "r")).template()).isEqualTo(NotificationTemplate.ORDER_FAILED);
        }

        @Test
        void fulfillmentEventDraftsOnlyKnowTheOrder() {
            var started = NotificationDraft.of(new EventPayloads.FulfillmentStarted(orderId, UUID.randomUUID()));
            var completed = NotificationDraft.of(new EventPayloads.FulfillmentCompleted(orderId, UUID.randomUUID(), "TRK-9"));
            var failed = NotificationDraft.of(new EventPayloads.FulfillmentFailed(orderId, UUID.randomUUID(), "x", 3));
            assertThat(List.of(started, completed, failed)).allSatisfy(d -> {
                assertThat(d.customerId()).isNull();
                assertThat(d.customerEmail()).isNull();
                assertThat(d.orderId()).isEqualTo(orderId);
            });
            assertThat(started.template()).isEqualTo(NotificationTemplate.FULFILLMENT_STARTED);
            assertThat(completed.subject()).contains("TRK-9");
            assertThat(failed.template()).isEqualTo(NotificationTemplate.FULFILLMENT_FAILED);
            assertThat(started.template().eventType()).isEqualTo(EventTypes.FULFILLMENT_STARTED);
        }
    }

    @Nested
    class Backoff {
        @Test
        void growsExponentiallyAndIsCapped() {
            NotificationProperties p = new NotificationProperties();
            p.setBackoffInitialMs(100);
            p.setBackoffMultiplier(2.0);
            p.setBackoffMaxMs(500);
            BackoffPolicy b = new BackoffPolicy(p);
            assertThat(b.delayMs(0)).isZero();
            assertThat(b.delayMs(1)).isEqualTo(100);
            assertThat(b.delayMs(2)).isEqualTo(200);
            assertThat(b.delayMs(3)).isEqualTo(400);
            assertThat(b.delayMs(4)).isEqualTo(500);
            assertThat(b.delayMs(50)).isEqualTo(500);
        }
    }

    @Nested
    class Sender {
        private final NotificationProperties props = new NotificationProperties();
        private final SimulatedSender sender = new SimulatedSender(props);

        private NotificationMessage msg(String recipient, int attempt) {
            return new NotificationMessage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    NotificationChannel.EMAIL, "ORDER_CREATED", "subject", recipient, attempt);
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 2})
        void failDomainFailsTheFirstNAttempts(int attempt) {
            SendResult r = sender.send(msg("ops@fail.example.test", attempt));
            assertThat(r.success()).isFalse();
            assertThat(r.error()).doesNotContain("@").doesNotContain("fail.example.test");
        }

        @Test
        void failDomainSucceedsAfterNFailures() {
            assertThat(sender.send(msg("ops@fail.example.test", 3)).success()).isTrue();
            assertThat(sender.send(msg("OPS@FAIL.EXAMPLE.TEST", 1)).success()).isFalse();
            props.setSimulatedFailures(4);
            assertThat(sender.send(msg("ops@fail.example.test", 4)).success()).isFalse();
            assertThat(sender.send(msg("ops@fail.example.test", 5)).success()).isTrue();
        }

        @Test
        void otherRecipientsAlwaysSucceedIncludingOddInput() {
            assertThat(sender.send(msg("procurement@acme.example.test", 1)).success()).isTrue();
            assertThat(sender.send(msg("x@notfail.example.test", 1)).success()).isTrue();
            assertThat(sender.send(msg("no-at-sign", 1)).success()).isTrue();
            assertThat(sender.send(msg(null, 1)).success()).isTrue();
        }
    }

    @Nested
    class EntityBehaviour {
        private Notification fresh() {
            return new Notification(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NotificationChannel.EMAIL,
                    NotificationTemplate.ORDER_CREATED, "s", "corr", UUID.randomUUID(), Instant.EPOCH);
        }

        @Test
        void failureThenSuccessCountsEveryAttempt() {
            Notification n = fresh();
            n.markAttemptFailed("e1", false, Instant.EPOCH.plusSeconds(1));
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.RETRYING);
            n.markAttemptFailed("e2", false, Instant.EPOCH.plusSeconds(2));
            n.markSent(Instant.EPOCH.plusSeconds(3));
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
            assertThat(n.getAttempts()).isEqualTo(3);
            assertThat(n.getLastError()).isNull();
            assertThat(n.getNextAttemptAt()).isNull();
            assertThat(n.getSentAt()).isNotNull();
        }

        @Test
        void exhaustedFailureThenRequeueGivesFreshBudget() {
            Notification n = fresh();
            n.markAttemptFailed("boom", true, null);
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
            assertThat(n.getNextAttemptAt()).isNull();
            n.requeue(Instant.EPOCH.plusSeconds(10));
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
            assertThat(n.getAttempts()).isZero();
            assertThat(n.getNextAttemptAt()).isEqualTo(Instant.EPOCH.plusSeconds(10));
        }

        @Test
        void sentIsTerminal() {
            Notification n = fresh();
            n.markSent(Instant.EPOCH);
            assertThatThrownBy(() -> n.requeue(Instant.EPOCH)).isInstanceOf(InvalidStateTransitionException.class);
            assertThatThrownBy(() -> n.markAttemptFailed("x", false, Instant.EPOCH))
                    .isInstanceOf(InvalidStateTransitionException.class);
        }
    }
}

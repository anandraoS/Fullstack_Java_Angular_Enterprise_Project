package com.enterprise.oms.common.idempotency;

import com.enterprise.oms.common.testapp.TestApplication;
import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.common.audit.AuditEventView;
import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.common.messaging.MessagingMetrics;
import com.enterprise.oms.common.web.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TestApplication.class)
class IdempotentProcessorAndAuditTest {

    @Autowired IdempotentEventProcessor processor;
    @Autowired ProcessedEventRepository processed;
    @Autowired MessagingMetrics metrics;
    @Autowired AuditService audit;

    private EventEnvelope<EventPayloads.FulfillmentStarted> event() {
        UUID orderId = UUID.randomUUID();
        return EventEnvelope.forOrder(EventTypes.FULFILLMENT_STARTED, orderId, "fulfillment-service", "c1", null,
                new EventPayloads.FulfillmentStarted(orderId, UUID.randomUUID()));
    }

    @Test
    void secondDeliveryOfTheSameEventIdIsADuplicate() {
        var env = event();
        AtomicInteger effects = new AtomicInteger();
        long dupBefore = metrics.duplicatesTotal();
        long procBefore = metrics.processedTotal();

        assertThat(processor.processOnce("svc-a", "t", env, effects::incrementAndGet)).isTrue();
        assertThat(processor.processOnce("svc-a", "t", env, effects::incrementAndGet)).isFalse();

        assertThat(effects).hasValue(1);
        assertThat(metrics.duplicatesTotal()).isEqualTo(dupBefore + 1);
        assertThat(metrics.processedTotal()).isEqualTo(procBefore + 1);
        assertThat(processed.existsById(new ProcessedEvent.Key("svc-a", env.eventId()))).isTrue();
    }

    @Test
    void sameEventIsProcessedOncePerConsumer() {
        var env = event();
        AtomicInteger effects = new AtomicInteger();
        assertThat(processor.processOnce("consumer-1", env, effects::incrementAndGet)).isTrue();
        assertThat(processor.processOnce("consumer-2", env, effects::incrementAndGet)).isTrue();
        assertThat(effects).hasValue(2);
    }

    @Test
    void failingEffectRollsBackTheProcessedMarkerSoRetryRunsAgain() {
        var env = event();
        assertThatThrownBy(() -> processor.processOnce("svc-b", env, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(processed.existsById(new ProcessedEvent.Key("svc-b", env.eventId()))).isFalse();

        AtomicInteger effects = new AtomicInteger();
        assertThat(processor.processOnce("svc-b", env, effects::incrementAndGet)).isTrue();
        assertThat(effects).hasValue(1);
    }

    @Test
    void auditRecordsActorFromSecurityContextAndPagesInContractShape() {
        String entityId = UUID.randomUUID().toString();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "operator-7", "n/a", AuthorityUtils.createAuthorityList("ROLE_OPERATOR")));
        try {
            audit.record("Order", entityId, "CANCELLED", "reason=customer request");
            audit.record("Order", entityId, "RETRIED", java.util.Map.of("step", "VALIDATE"));
        } finally {
            SecurityContextHolder.clearContext();
        }
        audit.record("Order", entityId, "RECONCILED", null);

        PageResponse<AuditEventView> page = audit.search("Order", entityId,
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "occurredAt")));
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.page()).isZero();
        assertThat(page.content()).hasSize(2);

        PageResponse<AuditEventView> all = audit.search("Order", entityId, PageRequest.of(0, 10, Sort.by("occurredAt")));
        assertThat(all.content()).extracting(AuditEventView::actor)
                .contains("operator-7").contains(AuditService.SYSTEM_ACTOR);
        assertThat(all.content()).extracting(AuditEventView::detail).contains("{\"step\":\"VALIDATE\"}");
        assertThat(audit.search("Order", "unknown-id", PageRequest.of(0, 5)).content()).isEmpty();
    }
}

package com.enterprise.oms.fulfillment;

import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.common.error.InvalidStateTransitionException;
import com.enterprise.oms.common.error.NotFoundException;
import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.events.EventPayloads;
import com.enterprise.oms.common.events.EventTypes;
import com.enterprise.oms.common.outbox.OutboxService;
import com.enterprise.oms.fulfillment.config.FulfillmentProperties;
import com.enterprise.oms.fulfillment.domain.Fulfillment;
import com.enterprise.oms.fulfillment.domain.FulfillmentRepository;
import com.enterprise.oms.fulfillment.domain.FulfillmentStatus;
import com.enterprise.oms.fulfillment.service.FailureSimulator;
import com.enterprise.oms.fulfillment.service.FulfillmentService;
import com.enterprise.oms.fulfillment.service.TrackingNumbers;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FulfillmentServiceTest {

    @Mock FulfillmentRepository repository;
    @Mock OutboxService outbox;
    @Mock AuditService audit;

    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private FulfillmentProperties props;
    private FulfillmentService service;

    @BeforeEach
    void setUp() {
        props = new FulfillmentProperties();
        props.setStepDelayMs(0);
        props.setMaxAttempts(3);
        service = new FulfillmentService(repository, outbox, audit, new FailureSimulator(props), props,
                new SimpleMeterRegistry(), clock);
    }

    private EventEnvelope<EventPayloads.StartFulfillment> command(UUID orderId, String city) {
        var payload = new EventPayloads.StartFulfillment(orderId,
                new EventPayloads.Address("1 Main St", city, "12345", "US"),
                List.of(new EventPayloads.SkuItem(UUID.randomUUID(), "SKU-1001", 2)));
        return EventEnvelope.forOrder(EventTypes.START_FULFILLMENT, orderId, "order-service", "corr-1", null, payload);
    }

    private Fulfillment pending(UUID orderId, String city) {
        return new Fulfillment(UUID.randomUUID(), orderId, "1 Main St", city, "12345", "US", 1, "corr-1",
                UUID.randomUUID(), now);
    }

    @Test
    void startCreatesPendingFulfillmentAndPublishesStarted() {
        UUID orderId = UUID.randomUUID();
        when(repository.existsByOrderId(orderId)).thenReturn(false);
        var env = command(orderId, "Springfield");

        Optional<Fulfillment> created = service.start(env, env.payload());

        assertThat(created).isPresent();
        assertThat(created.get().getStatus()).isEqualTo(FulfillmentStatus.PENDING);
        assertThat(created.get().getEvents()).hasSize(1);
        verify(repository).save(created.get());
        ArgumentCaptor<EventEnvelope<?>> out = ArgumentCaptor.captor();
        verify(outbox).append(out.capture());
        assertThat(out.getValue().eventType()).isEqualTo(EventTypes.FULFILLMENT_STARTED);
        assertThat(out.getValue().aggregateId()).isEqualTo(orderId.toString());
        assertThat(out.getValue().correlationId()).isEqualTo("corr-1");
        assertThat(out.getValue().causationId()).isEqualTo(env.eventId());
    }

    @Test
    void duplicateCommandForSameOrderIsNoOp() {
        UUID orderId = UUID.randomUUID();
        when(repository.existsByOrderId(orderId)).thenReturn(true);
        var env = command(orderId, "Springfield");

        assertThat(service.start(env, env.payload())).isEmpty();

        verify(repository, never()).save(any());
        verify(outbox, never()).append(any());
    }

    @Test
    void invalidCommandIsRejectedAsIllegalArgumentSoItIsNonRetryable() {
        UUID orderId = UUID.randomUUID();
        var noCity = command(orderId, " ");
        assertThatThrownBy(() -> service.start(noCity, noCity.payload())).isInstanceOf(IllegalArgumentException.class);
        var noOrder = command(orderId, "X");
        assertThatThrownBy(() -> service.start(noOrder, new EventPayloads.StartFulfillment(null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void advanceWalksTheHappyPathAndPublishesCompletedWithTracking() {
        UUID orderId = UUID.randomUUID();
        Fulfillment f = pending(orderId, "Springfield");
        when(repository.findById(f.getId())).thenReturn(Optional.of(f));

        for (FulfillmentStatus expected : List.of(FulfillmentStatus.PICKING, FulfillmentStatus.PACKED,
                FulfillmentStatus.SHIPPED, FulfillmentStatus.COMPLETED)) {
            assertThat(service.advance(f.getId())).isTrue();
            assertThat(f.getStatus()).isEqualTo(expected);
        }
        assertThat(service.advance(f.getId())).as("terminal: nothing to do").isFalse();

        assertThat(f.getTrackingNumber()).isEqualTo(TrackingNumbers.forOrder(orderId)).startsWith("OMS-");
        assertThat(f.getEvents()).extracting(e -> e.getStatus()).containsExactly(FulfillmentStatus.PENDING,
                FulfillmentStatus.PICKING, FulfillmentStatus.PACKED, FulfillmentStatus.SHIPPED,
                FulfillmentStatus.COMPLETED);
        ArgumentCaptor<EventEnvelope<?>> out = ArgumentCaptor.captor();
        verify(outbox).append(out.capture());
        assertThat(out.getValue().eventType()).isEqualTo(EventTypes.FULFILLMENT_COMPLETED);
        assertThat(((EventPayloads.FulfillmentCompleted) out.getValue().payload()).trackingNumber())
                .isEqualTo(f.getTrackingNumber());
    }

    @Test
    void failtownFailsEveryAttemptAndPublishesFailedAfterMaxAttempts() {
        Fulfillment f = pending(UUID.randomUUID(), "FAILTOWN");
        when(repository.findById(f.getId())).thenReturn(Optional.of(f));

        service.advance(f.getId());
        assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.PENDING);
        assertThat(f.getAttempts()).isEqualTo(1);
        verify(outbox, never()).append(any());
        service.advance(f.getId());
        assertThat(f.getAttempts()).isEqualTo(2);
        assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.PENDING);
        service.advance(f.getId());

        assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.FAILED);
        assertThat(f.getAttempts()).isEqualTo(3);
        assertThat(f.getFailureReason()).contains("FAILTOWN");
        assertThat(f.getNextStepAt()).isNull();
        ArgumentCaptor<EventEnvelope<?>> out = ArgumentCaptor.captor();
        verify(outbox).append(out.capture());
        assertThat(out.getValue().eventType()).isEqualTo(EventTypes.FULFILLMENT_FAILED);
        var payload = (EventPayloads.FulfillmentFailed) out.getValue().payload();
        assertThat(payload.attempts()).isEqualTo(3);
        assertThat(payload.fulfillmentId()).isEqualTo(f.getId());
        assertThat(service.advance(f.getId())).isFalse();
    }

    @Test
    void randomFailureRateIsHonouredAndSucceedsWhenRateIsZero() {
        props.setFailureRate(1.0);
        Fulfillment f = pending(UUID.randomUUID(), "Springfield");
        when(repository.findById(f.getId())).thenReturn(Optional.of(f));
        service.advance(f.getId());
        assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.PENDING);
        assertThat(f.getAttempts()).isEqualTo(1);
        assertThat(f.getFailureReason()).contains("random");

        props.setFailureRate(0.0);
        service.advance(f.getId());
        assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.PICKING);
        assertThat(f.getFailureReason()).isNull();
    }

    @Test
    void stepNotYetDueIsSkipped() {
        props.setStepDelayMs(60_000);
        Fulfillment f = pending(UUID.randomUUID(), "Springfield");
        f.scheduleNextStep(now.plusSeconds(60));
        when(repository.findById(f.getId())).thenReturn(Optional.of(f));
        assertThat(service.advance(f.getId())).isFalse();
        assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.PENDING);
    }

    @Test
    void retryMovesFailedBackToPendingAndResetsAttempts() {
        Fulfillment f = pending(UUID.randomUUID(), "FAILTOWN");
        when(repository.findById(f.getId())).thenReturn(Optional.of(f));
        props.setMaxAttempts(1);
        service.advance(f.getId());
        assertThat(f.getStatus()).isEqualTo(FulfillmentStatus.FAILED);

        var view = service.retry(f.getId());

        assertThat(view.status()).isEqualTo(FulfillmentStatus.PENDING);
        assertThat(f.getAttempts()).isZero();
        assertThat(f.getFailureReason()).isNull();
        assertThat(f.getNextStepAt()).isEqualTo(now);
        verify(audit).record(eq("Fulfillment"), eq(f.getId()), eq("RETRY_REQUESTED"), any());
    }

    @Test
    void retryOfNonFailedIsInvalidTransitionAndUnknownIsNotFound() {
        Fulfillment f = pending(UUID.randomUUID(), "Springfield");
        when(repository.findById(f.getId())).thenReturn(Optional.of(f));
        assertThatThrownBy(() -> service.retry(f.getId())).isInstanceOf(InvalidStateTransitionException.class);

        UUID unknown = UUID.randomUUID();
        lenient().when(repository.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.retry(unknown)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.get(unknown)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void recoverInFlightSchedulesUnscheduledRowsAndAudits() {
        Fulfillment f = pending(UUID.randomUUID(), "Springfield");
        f.clearSchedule();
        when(repository.findIdsByStatusIn(any())).thenReturn(List.of(f.getId()));
        when(repository.findById(f.getId())).thenReturn(Optional.of(f));

        assertThat(service.recoverInFlight()).isEqualTo(1);

        assertThat(f.getNextStepAt()).isEqualTo(now);
        verify(audit).record(eq("system"), eq("Fulfillment"), eq(f.getId()), eq("RESUMED_AFTER_RESTART"), any());
    }

    @Test
    void trackingNumberIsDeterministicPerOrder() {
        UUID a = UUID.randomUUID();
        assertThat(TrackingNumbers.forOrder(a)).isEqualTo(TrackingNumbers.forOrder(a))
                .matches("OMS-[0-9A-F]{12}");
        assertThat(TrackingNumbers.forOrder(a)).isNotEqualTo(TrackingNumbers.forOrder(UUID.randomUUID()));
    }

    /**
     * Regression (integration run, correlation trace): the scheduler thread that advances a fulfillment has no MDC, and the
     * service logged nothing on the success path. Lifecycle lines now carry the stored correlation id.
     */
    @Test
    void schedulerStepsLogTheStoredCorrelationIdEvenWithoutMdc() {
        Logger logger = (Logger) LoggerFactory.getLogger(FulfillmentService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MDC.clear();
            Fulfillment f = pending(UUID.randomUUID(), "Springfield");
            f.scheduleNextStep(now);
            when(repository.findById(f.getId())).thenReturn(Optional.of(f));

            assertThat(service.advance(f.getId())).isTrue();

            ILoggingEvent line = appender.list.stream().filter(e -> e.getFormattedMessage().contains("is now PICKING"))
                    .findFirst().orElseThrow();
            assertThat(line.getMDCPropertyMap()).containsEntry("correlationId", "corr-1");
            assertThat(MDC.get("correlationId")).as("MDC is restored").isNull();
        } finally {
            logger.detachAppender(appender);
        }
    }
}

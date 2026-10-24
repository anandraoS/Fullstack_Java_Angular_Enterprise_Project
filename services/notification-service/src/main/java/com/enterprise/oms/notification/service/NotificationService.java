package com.enterprise.oms.notification.service;

import com.enterprise.oms.common.audit.AuditService;
import com.enterprise.oms.common.error.NotFoundException;
import com.enterprise.oms.common.events.EventEnvelope;
import com.enterprise.oms.common.observability.LogMasking;
import com.enterprise.oms.common.observability.Metrics;
import com.enterprise.oms.common.web.CorrelationIds;
import com.enterprise.oms.common.web.PageResponse;
import com.enterprise.oms.notification.api.NotificationView;
import com.enterprise.oms.notification.config.NotificationProperties;
import com.enterprise.oms.notification.domain.Notification;
import com.enterprise.oms.notification.domain.NotificationAttempt;
import com.enterprise.oms.notification.domain.NotificationAttemptRepository;
import com.enterprise.oms.notification.domain.NotificationChannel;
import com.enterprise.oms.notification.domain.NotificationRepository;
import com.enterprise.oms.notification.domain.NotificationStatus;
import com.enterprise.oms.notification.domain.OrderContact;
import com.enterprise.oms.notification.domain.OrderContactRepository;
import com.enterprise.oms.notification.sender.NotificationMessage;
import com.enterprise.oms.notification.sender.NotificationSender;
import com.enterprise.oms.notification.sender.SendResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Notification use cases: ingest an event into a PENDING notification (idempotent, duplicate protected by
 * {@code unique(source_event_id, template)}), deliver due notifications through the {@link NotificationSender}
 * (retry with exponential backoff), and operator retry of FAILED ones.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final String ENTITY = "Notification";
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_EMAIL = 320;

    private final NotificationRepository notifications;
    private final NotificationAttemptRepository attempts;
    private final OrderContactRepository contacts;
    private final NotificationSender sender;
    private final BackoffPolicy backoff;
    private final NotificationProperties props;
    private final AuditService audit;
    private final MeterRegistry registry;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, NotificationAttemptRepository attempts,
                               OrderContactRepository contacts, NotificationSender sender, BackoffPolicy backoff,
                               NotificationProperties props, AuditService audit, MeterRegistry registry, Clock clock) {
        this.notifications = notifications;
        this.attempts = attempts;
        this.contacts = contacts;
        this.sender = sender;
        this.backoff = backoff;
        this.props = props;
        this.audit = audit;
        this.registry = registry;
        this.clock = clock;
        Metrics.gauge(registry, "oms.notification.pending", this, s -> s.notifications.countByStatusIn(
                EnumSet.of(NotificationStatus.PENDING, NotificationStatus.RETRYING)));
    }

    // ---- ingestion --------------------------------------------------------------------------------------------

    /**
     * Records the notification an event asks for. Safe to call twice for the same event: the second call is a no-op.
     *
     * @return the created notification, or empty if this (event, template) pair already has one
     * @throws IllegalArgumentException     invalid event content (non-retryable, goes to the DLT)
     * @throws ContactUnavailableException  fulfillment event before the order's contact is known (retryable)
     */
    @Transactional
    public Optional<Notification> ingest(EventEnvelope<?> env, NotificationDraft draft) {
        if (draft.orderId() == null) {
            throw new IllegalArgumentException(env.eventType() + ".orderId is required");
        }
        Instant now = clock.instant();
        OrderContact contact = resolveContact(draft, now);
        UUID customerId = draft.customerId() != null ? draft.customerId() : contact.getCustomerId();
        String template = draft.template().name();
        if (notifications.existsBySourceEventIdAndTemplate(env.eventId(), template)) {
            Metrics.counter(registry, "oms.notification.duplicates", "template", template).increment();
            log.info("Duplicate notification skipped: event={} template={}", env.eventId(), template);
            return Optional.empty();
        }
        String correlationId = env.correlationId() != null ? env.correlationId() : CorrelationIds.current();
        Notification n = new Notification(UUID.randomUUID(), customerId, draft.orderId(), NotificationChannel.EMAIL,
                draft.template(), draft.subject(), correlationId, env.eventId(), now);
        notifications.save(n);
        audit.record("system", ENTITY, n.getId(), "CREATED",
                Map.of("template", template, "orderId", draft.orderId().toString(),
                        "sourceEventId", env.eventId().toString()));
        Metrics.counter(registry, "oms.notification.created", "template", template, "channel", "EMAIL").increment();
        log.info("Notification {} created: template={} order={} to={}", n.getId(), template, draft.orderId(),
                LogMasking.maskEmail(contact.getCustomerEmail()));
        return Optional.of(n);
    }

    private OrderContact resolveContact(NotificationDraft d, Instant now) {
        String email = d.customerEmail() == null ? null : d.customerEmail().trim();
        if (email != null && !email.isEmpty()) {
            if (email.length() > MAX_EMAIL || !EMAIL.matcher(email).matches()) {
                throw new IllegalArgumentException("customerEmail is not a valid e-mail address");
            }
            UUID customerId = d.customerId();
            if (customerId == null) {
                OrderContact existing = contacts.findById(d.orderId()).orElse(null);
                if (existing == null) {
                    throw new IllegalArgumentException("customerId is required");
                }
                customerId = existing.getCustomerId();
            }
            OrderContact contact = contacts.findById(d.orderId()).orElse(null);
            if (contact == null) {
                return contacts.save(new OrderContact(d.orderId(), customerId, email, now));
            }
            if (!contact.getCustomerEmail().equals(email) || !contact.getCustomerId().equals(customerId)) {
                contact.update(customerId, email, now);
            }
            return contact;
        }
        OrderContact known = contacts.findById(d.orderId()).orElse(null);
        if (known != null) {
            return known;
        }
        if (d.customerId() != null) {
            // an order event that should have carried the address: malformed, not worth retrying
            throw new IllegalArgumentException("customerEmail is required");
        }
        throw new ContactUnavailableException(d.orderId());
    }

    // ---- delivery ---------------------------------------------------------------------------------------------

    /**
     * Performs one delivery attempt if the notification is PENDING/RETRYING and due. One transaction per attempt.
     * (The simulated sender is in-process; a real provider call should happen outside the DB transaction.)
     *
     * @return true if an attempt was made
     */
    @Transactional
    public boolean deliver(UUID id) {
        Notification n = notifications.findById(id).orElse(null);
        if (n == null || !n.getStatus().isDeliverable()) {
            return false;
        }
        Instant now = clock.instant();
        if (n.getNextAttemptAt() != null && n.getNextAttemptAt().isAfter(now)) {
            return false;
        }
        String previous = MDC.get(CorrelationIds.MDC_KEY);
        if (CorrelationIds.isValid(n.getCorrelationId())) {
            MDC.put(CorrelationIds.MDC_KEY, n.getCorrelationId());
        }
        try {
            int attemptNo = n.getAttempts() + 1;
            OrderContact contact = contacts.findById(n.getOrderId()).orElse(null);
            SendResult result;
            if (contact == null) {
                result = SendResult.failure("Recipient unknown for order");
            } else {
                try {
                    result = sender.send(new NotificationMessage(n.getId(), n.getOrderId(), n.getCustomerId(),
                            n.getChannel(), n.getTemplate(), n.getSubject(), contact.getCustomerEmail(), attemptNo));
                } catch (RuntimeException e) {
                    result = SendResult.failure("Sender error: " + e.getClass().getSimpleName());
                }
            }
            String error = result.success() ? null : abbreviate(LogMasking.sanitize(
                    result.error() == null ? "delivery failed" : result.error()));
            attempts.save(new NotificationAttempt(UUID.randomUUID(), n.getId(), attemptNo,
                    result.success() ? NotificationAttempt.SUCCESS : NotificationAttempt.FAILURE, error, now));
            if (result.success()) {
                n.markSent(now);
                audit.record("system", ENTITY, n.getId(), "SENT", Map.of("attempt", attemptNo));
                Metrics.counter(registry, "oms.notification.sent", "template", n.getTemplate()).increment();
                log.info("Notification {} SENT after {} attempt(s)", n.getId(), attemptNo);
            } else {
                boolean exhausted = attemptNo >= props.getMaxAttempts();
                long delay = backoff.delayMs(attemptNo);
                n.markAttemptFailed(error, exhausted, now.plusMillis(delay));
                audit.record("system", ENTITY, n.getId(), exhausted ? "FAILED" : "ATTEMPT_FAILED",
                        Map.of("attempt", attemptNo, "error", error, "nextDelayMs", exhausted ? 0 : delay));
                Metrics.counter(registry, exhausted ? "oms.notification.failed" : "oms.notification.retries",
                        "template", n.getTemplate()).increment();
                log.warn("Notification {} attempt {}/{} failed ({}){}", n.getId(), attemptNo, props.getMaxAttempts(),
                        error, exhausted ? ", giving up" : ", retry in " + delay + " ms");
            }
            return true;
        } finally {
            if (previous == null) {
                MDC.remove(CorrelationIds.MDC_KEY);
            } else {
                MDC.put(CorrelationIds.MDC_KEY, previous);
            }
        }
    }

    // ---- admin ------------------------------------------------------------------------------------------------

    /** Operator retry: FAILED -> PENDING with a fresh attempt budget; other states -> 409 INVALID_STATE_TRANSITION. */
    @Transactional
    public NotificationView retry(UUID id) {
        Notification n = notifications.findById(id).orElseThrow(() -> new NotFoundException(ENTITY, id));
        n.requeue(clock.instant());
        audit.record(ENTITY, n.getId(), "RETRY_REQUESTED", Map.of("orderId", n.getOrderId().toString()));
        Metrics.counter(registry, "oms.notification.manual_retries", "template", n.getTemplate()).increment();
        return NotificationView.of(n);
    }

    // ---- queries ----------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<NotificationView> search(UUID customerId, UUID orderId, NotificationStatus status,
                                                 Pageable pageable) {
        Specification<Notification> spec = Specification.where(null);
        if (customerId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("customerId"), customerId));
        }
        if (orderId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("orderId"), orderId));
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        return PageResponse.from(notifications.findAll(spec, pageable), NotificationView::of);
    }

    @Transactional(readOnly = true)
    public List<NotificationAttempt> attemptsOf(UUID notificationId) {
        return attempts.findByNotificationIdOrderByAttemptedAtAsc(notificationId);
    }

    private static String abbreviate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}

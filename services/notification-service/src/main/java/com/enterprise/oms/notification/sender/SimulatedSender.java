package com.enterprise.oms.notification.sender;

import com.enterprise.oms.common.observability.LogMasking;
import com.enterprise.oms.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Simulated delivery: never contacts a real provider. Recipients in domain {@value #FAILING_DOMAIN} fail their first
 * {@code oms.notification.simulated-failures} attempts (default 2) and succeed afterwards; every other recipient
 * succeeds immediately. Stateless: the outcome depends only on the attempt number supplied by the caller.
 */
public class SimulatedSender implements NotificationSender {

    public static final String FAILING_DOMAIN = "fail.example.test";

    private static final Logger log = LoggerFactory.getLogger(SimulatedSender.class);

    private final NotificationProperties props;

    public SimulatedSender(NotificationProperties props) {
        this.props = props;
    }

    @Override
    public SendResult send(NotificationMessage m) {
        String recipient = m.recipient() == null ? "" : m.recipient();
        int at = recipient.lastIndexOf('@');
        String domain = at < 0 ? "" : recipient.substring(at + 1).toLowerCase(Locale.ROOT);
        if (FAILING_DOMAIN.equals(domain) && m.attemptNumber() <= props.getSimulatedFailures()) {
            log.info("Simulated {} delivery FAILED: notification={} template={} to={} attempt={}", m.channel(),
                    m.notificationId(), m.template(), LogMasking.maskEmail(recipient), m.attemptNumber());
            return SendResult.failure("Simulated provider failure (attempt " + m.attemptNumber() + ")");
        }
        log.info("Simulated {} delivery OK: notification={} template={} to={} attempt={}", m.channel(),
                m.notificationId(), m.template(), LogMasking.maskEmail(recipient), m.attemptNumber());
        return SendResult.ok();
    }
}

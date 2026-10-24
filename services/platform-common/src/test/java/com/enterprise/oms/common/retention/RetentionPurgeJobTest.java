package com.enterprise.oms.common.retention;

import com.enterprise.oms.common.audit.AuditEvent;
import com.enterprise.oms.common.audit.AuditEventRepository;
import com.enterprise.oms.common.idempotency.ProcessedEvent;
import com.enterprise.oms.common.idempotency.ProcessedEventRepository;
import com.enterprise.oms.common.testapp.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs against the H2 test context: old rows are purged, recent rows survive, disabled job deletes nothing. */
@SpringBootTest(classes = TestApplication.class)
class RetentionPurgeJobTest {

    @Autowired RetentionPurgeJob job;
    @Autowired RetentionProperties props;
    @Autowired ProcessedEventRepository processed;
    @Autowired AuditEventRepository audit;

    @Test
    void purgesOnlyRowsOlderThanRetention() {
        UUID oldEvent = UUID.randomUUID();
        UUID freshEvent = UUID.randomUUID();
        processed.save(new ProcessedEvent("purge-test", oldEvent, "T", "t",
                Instant.now().minus(props.getProcessedEventsRetention()).minus(Duration.ofDays(1))));
        processed.save(new ProcessedEvent("purge-test", freshEvent, "T", "t", Instant.now()));
        UUID oldAudit = UUID.randomUUID();
        UUID freshAudit = UUID.randomUUID();
        audit.save(new AuditEvent(oldAudit, Instant.now().minus(props.getAuditEventsRetention()).minus(Duration.ofDays(1)),
                "system", "Order", "o-1", "TEST", null, null));
        audit.save(new AuditEvent(freshAudit, Instant.now(), "system", "Order", "o-2", "TEST", null, null));

        job.purge();

        assertThat(processed.existsById(new ProcessedEvent.Key("purge-test", oldEvent))).isFalse();
        assertThat(processed.existsById(new ProcessedEvent.Key("purge-test", freshEvent))).isTrue();
        assertThat(audit.existsById(oldAudit)).isFalse();
        assertThat(audit.existsById(freshAudit)).isTrue();
    }

    @Test
    void disabledJobDeletesNothing() {
        UUID oldEvent = UUID.randomUUID();
        processed.save(new ProcessedEvent("purge-test-2", oldEvent, "T", "t", Instant.parse("2000-01-01T00:00:00Z")));
        props.setEnabled(false);
        try {
            job.purge();
        } finally {
            props.setEnabled(true);
        }
        assertThat(processed.existsById(new ProcessedEvent.Key("purge-test-2", oldEvent))).isTrue();
        job.purge();
        assertThat(processed.existsById(new ProcessedEvent.Key("purge-test-2", oldEvent))).isFalse();
    }
}

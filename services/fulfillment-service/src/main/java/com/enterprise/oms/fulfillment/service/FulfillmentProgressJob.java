package com.enterprise.oms.fulfillment.service;

import com.enterprise.oms.fulfillment.config.FulfillmentProperties;
import com.enterprise.oms.fulfillment.domain.FulfillmentRepository;
import com.enterprise.oms.fulfillment.domain.FulfillmentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Scheduler that drives the simulated lifecycle: every poll it advances all due in-flight fulfillments by one step.
 * Stateless (progress is in the DB), safe with several instances (optimistic locking on {@code version}; the loser
 * just skips and the next poll picks the row up again).
 */
@Component
public class FulfillmentProgressJob {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentProgressJob.class);

    private final FulfillmentRepository repository;
    private final FulfillmentService service;
    private final FulfillmentProperties props;
    private final Clock clock;

    public FulfillmentProgressJob(FulfillmentRepository repository, FulfillmentService service,
                                  FulfillmentProperties props, Clock clock) {
        this.repository = repository;
        this.service = service;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${oms.fulfillment.poll-interval-ms:250}", initialDelayString = "${oms.fulfillment.poll-interval-ms:250}")
    public void poll() {
        if (!props.isSchedulerEnabled()) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("Fulfillment progress poll failed: {}", e.toString());
        }
    }

    /** One scheduling pass (also used directly by tests). @return number of fulfillments advanced */
    public int runOnce() {
        List<UUID> due = repository.findDueIds(EnumSet.of(FulfillmentStatus.PENDING, FulfillmentStatus.PICKING,
                FulfillmentStatus.PACKED, FulfillmentStatus.SHIPPED), clock.instant(),
                PageRequest.of(0, props.getBatchSize()));
        int advanced = 0;
        for (UUID id : due) {
            try {
                if (service.advance(id)) {
                    advanced++;
                }
            } catch (OptimisticLockingFailureException e) {
                log.debug("Fulfillment {} advanced concurrently, skipping", id);
            } catch (RuntimeException e) {
                log.warn("Advancing fulfillment {} failed: {}", id, e.toString());
            }
        }
        return advanced;
    }
}

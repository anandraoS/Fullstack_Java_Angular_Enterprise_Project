package com.enterprise.oms.common.messaging;

import io.micrometer.core.instrument.MeterRegistry;

import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Messaging counters (contract section 7): {@code oms.events.processed{service,topic,type}},
 * {@code oms.events.duplicates}, {@code oms.events.retries}, {@code oms.events.dead_lettered},
 * plus {@code oms.events.ignored} (unknown event type). Also keeps in-process totals for the ops endpoint.
 * The {@code service} tag is added globally by the platform (common tag), the rest here.
 */
public class MessagingMetrics {

    public static final String PROCESSED = "oms.events.processed";
    public static final String DUPLICATES = "oms.events.duplicates";
    public static final String RETRIES = "oms.events.retries";
    public static final String DEAD_LETTERED = "oms.events.dead_lettered";
    public static final String IGNORED = "oms.events.ignored";

    private final MeterRegistry registry;
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong deadLettered = new AtomicLong();
    private final AtomicLong ignored = new AtomicLong();
    private final AtomicReference<Instant> lastProcessedAt = new AtomicReference<>();

    public MessagingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Registers the failure-path counters at 0 for every configured topic. Micrometer creates a counter lazily on its first
     * increment, so before this a healthy service exposed no {@code oms_events_retries_total} /
     * {@code oms_events_dead_lettered_total} / {@code oms_events_duplicates_total} series at all: dashboards showed "No data"
     * and {@code increase(...)} alert expressions had nothing to evaluate (found by scripts/check-metric-names.mjs).
     */
    public void preRegister(Collection<String> topics) {
        if (topics == null) {
            return;
        }
        for (String topic : topics) {
            String t = nz(topic == null ? null : topic.trim());
            registry.counter(RETRIES, "topic", t);
            registry.counter(DEAD_LETTERED, "topic", t);
            registry.counter(DUPLICATES, "topic", t, "type", "none"); // same tag keys as duplicate(topic, type)
        }
    }

    public void processed(String topic, String type) {
        processed.incrementAndGet();
        lastProcessedAt.set(Instant.now());
        registry.counter(PROCESSED, "topic", nz(topic), "type", nz(type)).increment();
    }

    public void duplicate(String topic, String type) {
        duplicates.incrementAndGet();
        registry.counter(DUPLICATES, "topic", nz(topic), "type", nz(type)).increment();
    }

    public void retry(String topic) {
        retries.incrementAndGet();
        registry.counter(RETRIES, "topic", nz(topic)).increment();
    }

    public void deadLettered(String topic) {
        deadLettered.incrementAndGet();
        registry.counter(DEAD_LETTERED, "topic", nz(topic)).increment();
    }

    public void ignored(String topic, String type) {
        ignored.incrementAndGet();
        registry.counter(IGNORED, "topic", nz(topic), "type", nz(type)).increment();
    }

    public long processedTotal() { return processed.get(); }
    public long duplicatesTotal() { return duplicates.get(); }
    public long retriesTotal() { return retries.get(); }
    public long deadLetteredTotal() { return deadLettered.get(); }
    public long ignoredTotal() { return ignored.get(); }
    public Instant lastProcessedAt() { return lastProcessedAt.get(); }

    private static String nz(String s) {
        return s == null ? "unknown" : s;
    }
}

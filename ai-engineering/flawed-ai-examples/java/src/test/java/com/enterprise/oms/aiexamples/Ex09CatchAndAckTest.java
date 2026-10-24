package com.enterprise.oms.aiexamples;

import com.enterprise.oms.aiexamples.Ex09CatchAndAck.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Ex09CatchAndAckTest {

    private static Handler recordingHandler(List<String> processed, int transientFailuresForOffset0) {
        int[] failures = {0};
        return r -> {
            if (r.payload().equals("poison")) throw new NonRetryable("malformed payload");
            if (r.offset() == 0 && failures[0]++ < transientFailuresForOffset0) throw new TransientFailure("db blip");
            processed.add(r.payload());
        };
    }

    @Test
    void flawedListenerLosesRecordOnTransientFailure() {
        Broker broker = new Broker();
        List<String> processed = new ArrayList<>();
        FlawedListener l = new FlawedListener(broker, recordingHandler(processed, 1));

        l.poll(List.of(new Rec(0, "InventoryReserved:o-1")));

        assertTrue(processed.isEmpty(), "BUG MANIFESTS: the business effect never happened");
        assertEquals(0, broker.committedOffset, "BUG MANIFESTS: yet the offset was committed, so it will never be redelivered");
        assertTrue(broker.dlt.isEmpty(), "and nothing was dead-lettered for operators to find");
    }

    @Test
    void fixedListenerRetriesTransientFailureAndThenAcks() {
        Broker broker = new Broker();
        List<String> processed = new ArrayList<>();
        FixedListener l = new FixedListener(broker, recordingHandler(processed, 2));

        l.poll(List.of(new Rec(0, "InventoryReserved:o-1")));

        assertEquals(List.of("InventoryReserved:o-1"), processed);
        assertEquals(3, l.attemptsMade);
        assertEquals(0, broker.committedOffset);
        assertTrue(broker.dlt.isEmpty());
    }

    @Test
    void fixedListenerDeadLettersPoisonImmediatelyWithoutRetries() {
        Broker broker = new Broker();
        List<String> processed = new ArrayList<>();
        FixedListener l = new FixedListener(broker, recordingHandler(processed, 0));

        l.poll(List.of(new Rec(0, "poison"), new Rec(1, "InventoryReserved:o-2")));

        assertEquals(1, broker.dlt.size());
        assertTrue(broker.dlt.get(0).contains("NonRetryable"));
        assertEquals(List.of("InventoryReserved:o-2"), processed, "the partition is not blocked by the poison record");
        assertEquals(1, broker.committedOffset);
        assertEquals(2, l.attemptsMade, "1 attempt for poison + 1 for the good record");
    }

    @Test
    void fixedListenerDeadLettersAfterExhaustingRetries() {
        Broker broker = new Broker();
        FixedListener l = new FixedListener(broker, r -> { throw new TransientFailure("still down"); });

        l.poll(List.of(new Rec(0, "x")));

        assertEquals(FixedListener.MAX_ATTEMPTS, l.attemptsMade);
        assertEquals(1, broker.dlt.size());
        assertEquals(0, broker.committedOffset);
    }

    @Test
    void fixedListenerDoesNotAckWhenDeadLetterPublishFails() {
        Broker broker = new Broker();
        broker.dltDown = true;
        FixedListener l = new FixedListener(broker, r -> { throw new NonRetryable("bad"); });

        l.poll(List.of(new Rec(0, "poison")));

        assertEquals(-1, broker.committedOffset, "record stays uncommitted and will be redelivered");
    }
}

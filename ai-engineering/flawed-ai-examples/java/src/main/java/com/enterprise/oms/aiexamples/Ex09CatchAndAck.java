package com.enterprise.oms.aiexamples;

import java.util.ArrayList;
import java.util.List;

/**
 * Example 09: catching Exception and acknowledging. A tiny model of a Kafka listener: a record list, a committed
 * offset, a handler that can fail transiently or permanently. The real policy is in contract section 7: 4 attempts
 * with backoff, then {@code <topic>.DLT}; deserialization/validation/IllegalArgumentException are non-retryable
 * ({@code NonRetryableEventException}, {@code DefaultErrorHandler} + {@code DeadLetterPublishingRecoverer}).
 */
public final class Ex09CatchAndAck {

    private Ex09CatchAndAck() { }

    public record Rec(long offset, String payload) { }

    public static class TransientFailure extends RuntimeException {
        public TransientFailure(String m) { super(m); }
    }

    public static class NonRetryable extends RuntimeException {
        public NonRetryable(String m) { super(m); }
    }

    public interface Handler { void handle(Rec r); }

    public static final class Broker {
        public long committedOffset = -1;
        public final List<String> dlt = new ArrayList<>();
        public boolean dltDown;

        void publishDlt(Rec r, Exception cause) {
            if (dltDown) throw new IllegalStateException("DLT unavailable");
            dlt.add(r.payload() + " <- " + cause.getClass().getSimpleName());
        }
    }

    /** Flawed: "never let the listener crash". Every failure is logged and the offset advances. */
    public static final class FlawedListener {
        private final Broker broker;
        private final Handler handler;
        public final List<String> logged = new ArrayList<>();

        public FlawedListener(Broker broker, Handler handler) { this.broker = broker; this.handler = handler; }

        public void poll(List<Rec> batch) {
            for (Rec r : batch) {
                try {
                    handler.handle(r);
                } catch (Exception e) {
                    logged.add("error processing " + r.offset() + ": " + e.getMessage());   // and carry on
                }
                broker.committedOffset = r.offset();                                       // ack regardless
            }
        }
    }

    /** Fixed: retry transient errors, dead-letter permanent ones, ack only after success or a successful DLT write. */
    public static final class FixedListener {
        public static final int MAX_ATTEMPTS = 4;
        private final Broker broker;
        private final Handler handler;
        public int attemptsMade;

        public FixedListener(Broker broker, Handler handler) { this.broker = broker; this.handler = handler; }

        /** Stops (without acking) at the first record that can neither be processed nor dead-lettered. */
        public void poll(List<Rec> batch) {
            for (Rec r : batch) {
                if (!processWithPolicy(r)) return;
                broker.committedOffset = r.offset();
            }
        }

        private boolean processWithPolicy(Rec r) {
            Exception last = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                attemptsMade++;
                try {
                    handler.handle(r);
                    return true;
                } catch (NonRetryable e) {
                    return deadLetter(r, e);                     // retrying cannot help
                } catch (RuntimeException e) {
                    last = e;                                    // transient: back off (omitted) and retry
                }
            }
            return deadLetter(r, last);
        }

        private boolean deadLetter(Rec r, Exception cause) {
            try {
                broker.publishDlt(r, cause);
                return true;
            } catch (IllegalStateException dltDown) {
                return false;                                    // do NOT ack: the record will be redelivered
            }
        }
    }
}

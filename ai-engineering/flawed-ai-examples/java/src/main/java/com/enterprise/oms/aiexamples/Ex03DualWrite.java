package com.enterprise.oms.aiexamples;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Example 03: publishing to Kafka inside (or next to) a DB transaction, versus the transactional outbox.
 * See docs/adr/0009 and services/platform-common {@code com.enterprise.oms.common.outbox}.
 *
 * <p>{@link Db} buffers writes and applies them on commit (rollback discards them); {@link Broker} can be "down".
 */
public final class Ex03DualWrite {

    private Ex03DualWrite() { }

    public static final class Db {
        public final List<String> orders = new ArrayList<>();
        public final Map<String, Boolean> outbox = new LinkedHashMap<>();   // event -> published?
        public boolean failCommit;

        public void inTx(Consumer<Tx> body) {
            Tx tx = new Tx();
            body.accept(tx);                                  // an exception here == rollback (nothing applied)
            if (failCommit) throw new IllegalStateException("commit failed");
            orders.addAll(tx.orders);
            tx.outbox.forEach(e -> outbox.put(e, false));
        }

        public static final class Tx {
            private final List<String> orders = new ArrayList<>();
            private final List<String> outbox = new ArrayList<>();
            public void insertOrder(String id) { orders.add(id); }
            public void insertOutbox(String event) { outbox.add(event); }
        }
    }

    public static final class Broker {
        public final List<String> published = new ArrayList<>();
        public boolean down;

        public void publish(String event) {
            if (down) throw new IllegalStateException("broker unavailable");
            published.add(event);
        }
    }

    /** Flawed variant A: publish inside the transaction. A later failure leaves a phantom event. */
    public static final class FlawedPublishInsideTx {
        private final Db db;
        private final Broker broker;
        public FlawedPublishInsideTx(Db db, Broker broker) { this.db = db; this.broker = broker; }

        public void createOrder(String id) {
            db.inTx(tx -> {
                tx.insertOrder(id);
                broker.publish("OrderCreated:" + id);        // external side effect cannot be rolled back
            });
        }
    }

    /** Flawed variant B: publish after commit. A crash or broker outage leaves a committed order with no event. */
    public static final class FlawedPublishAfterCommit {
        private final Db db;
        private final Broker broker;
        public FlawedPublishAfterCommit(Db db, Broker broker) { this.db = db; this.broker = broker; }

        public void createOrder(String id) {
            db.inTx(tx -> tx.insertOrder(id));
            broker.publish("OrderCreated:" + id);            // if this throws, the event is lost forever
        }
    }

    /** Fixed: the event is a row written in the same transaction; a relay publishes it later. */
    public static final class OutboxOrderService {
        private final Db db;
        public OutboxOrderService(Db db) { this.db = db; }

        public void createOrder(String id) {
            db.inTx(tx -> {
                tx.insertOrder(id);
                tx.insertOutbox("OrderCreated:" + id);       // atomic with the order row
            });
        }
    }

    public static final class OutboxRelay {
        private final Db db;
        private final Broker broker;
        /** Test seam: simulate a crash between publish and "mark published". */
        public boolean crashBeforeMarkingPublished;

        public OutboxRelay(Db db, Broker broker) { this.db = db; this.broker = broker; }

        /** @return number of events published in this poll */
        public int poll() {
            int n = 0;
            for (Map.Entry<String, Boolean> e : new ArrayList<>(db.outbox.entrySet())) {
                if (e.getValue()) continue;
                try {
                    broker.publish(e.getKey());              // at-least-once: publish first...
                    n++;
                    if (crashBeforeMarkingPublished) throw new IllegalStateException("crash");
                    db.outbox.put(e.getKey(), true);         // ...then mark; a crash in between re-publishes
                } catch (IllegalStateException ex) {
                    return n;                                // leave the row pending; next poll retries
                }
            }
            return n;
        }
    }
}

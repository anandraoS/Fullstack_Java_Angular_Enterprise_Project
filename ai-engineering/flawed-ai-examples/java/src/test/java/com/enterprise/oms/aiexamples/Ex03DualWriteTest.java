package com.enterprise.oms.aiexamples;

import com.enterprise.oms.aiexamples.Ex03DualWrite.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Ex03DualWriteTest {

    @Test
    void flawedPublishInsideTxEmitsPhantomEventWhenCommitFails() {
        Db db = new Db();
        Broker broker = new Broker();
        db.failCommit = true;

        assertThrows(IllegalStateException.class, () -> new FlawedPublishInsideTx(db, broker).createOrder("o-1"));

        assertTrue(db.orders.isEmpty(), "order was rolled back");
        assertEquals(List.of("OrderCreated:o-1"), broker.published,
                "BUG MANIFESTS: consumers were told about an order that does not exist");
    }

    @Test
    void flawedPublishAfterCommitLosesEventWhenBrokerIsDown() {
        Db db = new Db();
        Broker broker = new Broker();
        broker.down = true;

        assertThrows(IllegalStateException.class, () -> new FlawedPublishAfterCommit(db, broker).createOrder("o-1"));
        broker.down = false;

        assertEquals(List.of("o-1"), db.orders, "order committed");
        assertTrue(broker.published.isEmpty(), "BUG MANIFESTS: no event, and nothing remembers it must be sent");
    }

    @Test
    void outboxRollsBackEventTogetherWithOrder() {
        Db db = new Db();
        Broker broker = new Broker();
        db.failCommit = true;

        assertThrows(IllegalStateException.class, () -> new OutboxOrderService(db).createOrder("o-1"));

        assertTrue(db.orders.isEmpty());
        assertTrue(db.outbox.isEmpty());
        assertEquals(0, new OutboxRelay(db, broker).poll());
        assertTrue(broker.published.isEmpty(), "no phantom event");
    }

    @Test
    void outboxSurvivesBrokerOutageAndPublishesLater() {
        Db db = new Db();
        Broker broker = new Broker();
        new OutboxOrderService(db).createOrder("o-1");
        OutboxRelay relay = new OutboxRelay(db, broker);

        broker.down = true;
        assertEquals(0, relay.poll());
        assertEquals(false, db.outbox.get("OrderCreated:o-1"), "still pending, not lost");

        broker.down = false;
        assertEquals(1, relay.poll());
        assertEquals(List.of("OrderCreated:o-1"), broker.published);
        assertEquals(0, relay.poll(), "already published rows are not re-sent");
    }

    @Test
    void outboxIsAtLeastOnceNotExactlyOnce_consumersMustBeIdempotent() {
        Db db = new Db();
        Broker broker = new Broker();
        new OutboxOrderService(db).createOrder("o-1");
        OutboxRelay relay = new OutboxRelay(db, broker);

        relay.crashBeforeMarkingPublished = true;
        relay.poll();                                    // published, crashed before marking
        relay.crashBeforeMarkingPublished = false;
        relay.poll();                                    // restart: row still pending, published again

        assertEquals(List.of("OrderCreated:o-1", "OrderCreated:o-1"), broker.published,
                "duplicates are possible by design; see Ex04 for the consumer-side defence");
    }
}

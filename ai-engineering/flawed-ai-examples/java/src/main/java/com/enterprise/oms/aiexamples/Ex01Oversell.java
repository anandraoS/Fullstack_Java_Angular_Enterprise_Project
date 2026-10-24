package com.enterprise.oms.aiexamples;

/**
 * Example 01: check-then-act overselling race.
 *
 * <p>The real system does this in inventory-service with one conditional SQL statement
 * ({@code UPDATE stock SET reserved = reserved + :q WHERE product_id = :id AND on_hand - reserved >= :q}),
 * see docs/adr/0006 and contract section 7. Here {@link StockRow} models the database row: every single method is
 * atomic (like one SQL statement), but a read followed by a write is NOT.
 */
public final class Ex01Oversell {

    private Ex01Oversell() { }

    /** Models one row of the stock table. Each method is one atomic "statement". */
    public static final class StockRow {
        private int onHand;
        private int reserved;

        public StockRow(int onHand) { this.onHand = onHand; }

        public synchronized int available() { return onHand - reserved; }
        public synchronized int reserved() { return reserved; }
        public synchronized int onHand() { return onHand; }

        /** Plain "UPDATE stock SET reserved = reserved + q" with no guard. */
        public synchronized void addReserved(int q) { reserved += q; }

        /** Conditional UPDATE ... WHERE on_hand - reserved >= q ; returns "affected rows". */
        public synchronized int addReservedIfAvailable(int q) {
            if (onHand - reserved >= q) {
                reserved += q;
                return 1;
            }
            return 0;
        }
    }

    /** What an AI assistant typically writes: SELECT, if, UPDATE. */
    public static final class FlawedReservationService {
        private final StockRow row;
        private final Runnable betweenCheckAndAct;

        /** @param betweenCheckAndAct test seam modelling "another request runs here"; production code has none. */
        public FlawedReservationService(StockRow row, Runnable betweenCheckAndAct) {
            this.row = row;
            this.betweenCheckAndAct = betweenCheckAndAct;
        }

        public boolean reserve(int quantity) {
            if (row.available() >= quantity) {      // check
                betweenCheckAndAct.run();           // the race window
                row.addReserved(quantity);          // act
                return true;
            }
            return false;
        }
    }

    /** Corrected: the check and the write are one atomic conditional statement; 0 affected rows means insufficient. */
    public static final class FixedReservationService {
        private final StockRow row;
        private final Runnable betweenCheckAndAct;

        public FixedReservationService(StockRow row, Runnable betweenCheckAndAct) {
            this.row = row;
            this.betweenCheckAndAct = betweenCheckAndAct;
        }

        public boolean reserve(int quantity) {
            betweenCheckAndAct.run();               // same interleaving opportunity; it no longer matters
            return row.addReservedIfAvailable(quantity) == 1;
        }
    }
}

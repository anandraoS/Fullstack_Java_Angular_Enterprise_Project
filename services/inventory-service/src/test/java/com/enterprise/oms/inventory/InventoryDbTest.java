package com.enterprise.oms.inventory;

import com.enterprise.oms.inventory.repo.ReservationClaims;
import com.enterprise.oms.inventory.repo.StockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Schema, constraints, the slot-claim savepoint and the atomic conditional UPDATE against the real migrations. */
class InventoryDbTest extends AbstractInventoryTest {

    @Autowired StockRepository stock;
    @Autowired ReservationClaims claims;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    @Test
    void flywayAppliedCommonAndServiceMigrationsButNoSeedInTheDefaultProfile() {
        List<String> versions = jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success = TRUE", String.class);
        assertThat(versions).contains("1", "10").doesNotContain("1000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE sku LIKE 'SKU-10%'", Integer.class)).isZero();
    }

    @Test
    void allDocumentedTablesAndIndexesExist() {
        List<String> tables = jdbc.queryForList("SELECT LOWER(table_name) FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(tables).contains("products", "stock", "inventory_reservations", "inventory_reservation_items",
                "stock_movements", "outbox_events", "processed_events", "audit_events");
        List<String> indexes = jdbc.queryForList("SELECT LOWER(index_name) FROM information_schema.indexes WHERE table_schema = 'public'", String.class);
        assertThat(indexes).contains("ix_products_category", "ix_products_name", "ix_reservations_status_expiry",
                "ix_reservations_created", "ix_resitems_product", "ix_movements_product_time", "ix_movements_reference");
    }

    @Test
    void checkConstraintsStopOversellingAndNegativeStockAtTheDatabaseLevel() {
        UUID p = newProduct(10);
        assertThatThrownBy(() -> jdbc.update("UPDATE stock SET reserved = 11 WHERE product_id = ?", p))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE stock SET on_hand = -1 WHERE product_id = ?", p))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE stock SET reserved = 5, on_hand = 4 WHERE product_id = ?", p))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(onHand(p)).isEqualTo(10);
        assertThat(reserved(p)).isZero();
    }

    @Test
    void uniqueSkuUniqueOrderAndForeignKeysAreEnforced() {
        UUID p = newProduct(1);
        Timestamp now = Timestamp.from(Instant.now());
        assertThatThrownBy(() -> jdbc.update("INSERT INTO products (id, sku, name, category, price, currency, active, created_at, updated_at, version) "
                + "VALUES (?, ?, 'dup', 'c', 1.00, 'USD', TRUE, ?, ?, 0)", UUID.randomUUID(), sku(p), now, now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO stock (product_id, on_hand, reserved, version, updated_at) VALUES (?, 1, 0, 0, ?)",
                UUID.randomUUID(), now)).as("stock row needs a product").isInstanceOf(DataIntegrityViolationException.class);

        UUID order = UUID.randomUUID();
        String insert = "INSERT INTO inventory_reservations (id, order_id, status, created_at, updated_at, version) VALUES (?, ?, 'FAILED', ?, ?, 0)";
        jdbc.update(insert, UUID.randomUUID(), order, now, now);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), order, now, now))
                .as("one reservation per order").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO inventory_reservations (id, order_id, status, created_at, updated_at, version) "
                + "VALUES (?, ?, 'BOGUS', ?, ?, 0)", UUID.randomUUID(), UUID.randomUUID(), now, now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO inventory_reservation_items (id, reservation_id, product_id, quantity) VALUES (?, ?, ?, 1)",
                UUID.randomUUID(), UUID.randomUUID(), p)).as("item needs a reservation").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void slotClaimSucceedsOnceAndLeavesTheTransactionUsableAfterTheDuplicate() {
        UUID order = UUID.randomUUID();
        Instant now = Instant.now();
        Integer rows = tx().execute(status -> {
            assertThat(claims.claimReserved(UUID.randomUUID(), order, now.plusSeconds(60), now)).isTrue();
            assertThat(claims.claimReserved(UUID.randomUUID(), order, now.plusSeconds(60), now))
                    .as("second claim in the same tx hits the unique index").isFalse();
            assertThat(claims.claimReleasedTombstone(UUID.randomUUID(), order, "CANCELLATION", now)).isFalse();
            // the savepoint rollback keeps the surrounding transaction alive (matters on PostgreSQL)
            return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservations WHERE order_id = ?", Integer.class, order);
        });
        assertThat(rows).isEqualTo(1);
        assertThat(reservationStatus(order)).isEqualTo("RESERVED");
    }

    @Test
    void aRolledBackClaimFreesTheSlot() {
        UUID order = UUID.randomUUID();
        Instant now = Instant.now();
        tx().executeWithoutResult(status -> {
            assertThat(claims.claimReserved(UUID.randomUUID(), order, now.plusSeconds(60), now)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(reservationCount(order)).isZero();
        boolean again = tx().execute(s -> claims.claimReserved(UUID.randomUUID(), order, now.plusSeconds(60), now));
        assertThat(again).isTrue();
    }

    @Test
    void conditionalUpdateReservesOnlyWhatIsAvailableAndBumpsTheVersion() {
        UUID p = newProduct(5);
        long v0 = stockVersion(p);
        Instant now = Instant.now();

        int ok = tx().execute(s -> stock.tryReserve(p, 5, now));
        int shortBy1 = tx().execute(s -> stock.tryReserve(p, 1, now));

        assertThat(ok).isEqualTo(1);
        assertThat(shortBy1).as("0 rows = insufficient").isZero();
        assertThat(reserved(p)).isEqualTo(5);
        assertThat(stockVersion(p)).isEqualTo(v0 + 1);
    }

    @Test
    void compareAndSetFailsOnAStaleVersion() {
        UUID p = newProduct(5);
        long v0 = stockVersion(p);
        Instant now = Instant.now();
        int first = tx().execute(s -> stock.compareAndReserve(p, 1, v0, now));
        assertThat(first).isEqualTo(1);
        int second = tx().execute(s -> stock.compareAndReserve(p, 1, v0, now));
        assertThat(second).as("same stale version again").isZero();
        assertThat(reserved(p)).isEqualTo(1);
    }

    @Test
    void releaseAndShipRefuseToGoBelowZero() {
        UUID p = newProduct(5);
        Instant now = Instant.now();
        tx().execute(s -> stock.tryReserve(p, 2, now));
        int releaseTooMuch = tx().execute(s -> stock.release(p, 3, now));
        assertThat(releaseTooMuch).isZero();
        int shipTooMuch = tx().execute(s -> stock.ship(p, 3, now));
        assertThat(shipTooMuch).isZero();
        int shipOk = tx().execute(s -> stock.ship(p, 2, now));
        assertThat(shipOk).isEqualTo(1);
        assertThat(onHand(p)).isEqualTo(3);
        assertThat(reserved(p)).isZero();
        assertThat(String.valueOf(jdbc.queryForObject("SELECT sku FROM products WHERE id = ?", String.class, p)).toUpperCase(Locale.ROOT))
                .startsWith("T-");
    }
}

package com.enterprise.oms.inventory;

import com.enterprise.oms.common.events.EventPayloads.QuantityItem;
import com.enterprise.oms.testsupport.JwtTestTokens;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Shared Spring context (H2 PostgreSQL mode, Flyway V1 + V10, no seed, no Kafka listeners, relay off) and
 * fixtures that talk to the database with plain JDBC so assertions never depend on JPA caches.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractInventoryTest {

    @DynamicPropertySource
    static void jwt(DynamicPropertyRegistry registry) {
        JwtTestTokens.registerProperties(registry);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MockMvc mvc;

    @AfterEach
    void resetStrategy(@Autowired com.enterprise.oms.inventory.reservation.ReservationStrategies strategies,
                       @Autowired com.enterprise.oms.inventory.config.InventoryProperties props) {
        strategies.select(props.getReservationStrategy());   // back to the configured baseline after tests that switch
    }

    protected UUID newProduct(int onHand) {
        return newProduct(onHand, true);
    }

    /** Inserts a product + stock row; ids/skus are unique so tests never collide in the shared database. */
    protected UUID newProduct(int onHand, boolean active) {
        UUID id = UUID.randomUUID();
        String sku = "T-" + id.toString().substring(0, 8).toUpperCase();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO products (id, sku, name, description, category, price, currency, active, created_at, "
                + "updated_at, version) VALUES (?, ?, ?, 'test product', 'TestCat', 10.00, 'USD', ?, ?, ?, 0)",
                id, sku, "Test " + sku, active, now, now);
        jdbc.update("INSERT INTO stock (product_id, on_hand, reserved, version, updated_at) VALUES (?, ?, 0, 0, ?)",
                id, onHand, now);
        return id;
    }

    protected String sku(UUID productId) {
        return jdbc.queryForObject("SELECT sku FROM products WHERE id = ?", String.class, productId);
    }

    protected int onHand(UUID productId) {
        return jdbc.queryForObject("SELECT on_hand FROM stock WHERE product_id = ?", Integer.class, productId);
    }

    protected int reserved(UUID productId) {
        return jdbc.queryForObject("SELECT reserved FROM stock WHERE product_id = ?", Integer.class, productId);
    }

    protected long stockVersion(UUID productId) {
        return jdbc.queryForObject("SELECT version FROM stock WHERE product_id = ?", Long.class, productId);
    }

    protected int reservationCount(UUID orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservations WHERE order_id = ?", Integer.class,
                orderId);
    }

    protected String reservationStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM inventory_reservations WHERE order_id = ?", String.class,
                orderId);
    }

    protected int outboxCount(UUID orderId, String eventType) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                Integer.class, orderId.toString(), eventType);
    }

    protected List<String> outboxPayloads(UUID orderId, String eventType) {
        return jdbc.queryForList("SELECT payload FROM outbox_events WHERE aggregate_id = ? AND event_type = ? "
                + "ORDER BY created_at", String.class, orderId.toString(), eventType);
    }

    protected static QuantityItem item(UUID productId, int quantity) {
        return new QuantityItem(productId, quantity);
    }
}

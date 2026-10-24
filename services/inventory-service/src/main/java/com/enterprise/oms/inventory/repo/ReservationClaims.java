package com.enterprise.oms.inventory.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Claims the single reservation slot of an order (unique {@code order_id}): plain INSERT inside a JDBC savepoint.
 * <ul>
 *   <li>Concurrent claimers block on the unique index until the first one commits or rolls back (PostgreSQL and H2
 *       behave the same), so after a failed claim the winner's COMMITTED row is visible to the next statement.</li>
 *   <li>The savepoint keeps the surrounding transaction usable after the unique violation (in PostgreSQL a failed
 *       statement otherwise aborts the whole transaction).</li>
 *   <li>Plain JDBC on purpose, not a JPA native query: a failed JPA statement marks the Hibernate transaction
 *       rollback-only, which would poison the Kafka processed-event transaction. {@code ON CONFLICT DO NOTHING} was
 *       not used because H2's PostgreSQL mode rejects it and the same SQL must run on both databases.</li>
 * </ul>
 * Must be called inside a transaction (savepoints do not exist in auto-commit mode).
 */
@Component
public class ReservationClaims {

    private static final String INSERT_RESERVED = "INSERT INTO inventory_reservations "
            + "(id, order_id, status, expires_at, created_at, updated_at, version) VALUES (?, ?, 'RESERVED', ?, ?, ?, 0)";
    private static final String INSERT_TOMBSTONE = "INSERT INTO inventory_reservations "
            + "(id, order_id, status, release_reason, created_at, updated_at, version) VALUES (?, ?, 'RELEASED', ?, ?, ?, 0)";

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public ReservationClaims(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    /** @return true if this transaction now owns the order's slot as a RESERVED row; false if one already exists. */
    public boolean claimReserved(UUID id, UUID orderId, Instant expiresAt, Instant now) {
        return insert(INSERT_RESERVED, id, orderId, Timestamp.from(expiresAt), Timestamp.from(now), Timestamp.from(now));
    }

    /** Claims the slot as an already RELEASED tombstone (release arrived before / without a reservation). */
    public boolean claimReleasedTombstone(UUID id, UUID orderId, String reason, Instant now) {
        return insert(INSERT_TOMBSTONE, id, orderId, reason, Timestamp.from(now), Timestamp.from(now));
    }

    private boolean insert(String sql, Object... args) {
        Connection con = DataSourceUtils.getConnection(dataSource);
        try {
            Savepoint savepoint = con.setSavepoint();
            try {
                jdbc.update(sql, args);
                con.releaseSavepoint(savepoint);
                return true;
            } catch (DuplicateKeyException duplicate) {
                con.rollback(savepoint);
                return false;
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Savepoint handling failed while claiming a reservation slot", e);
        } finally {
            DataSourceUtils.releaseConnection(con, dataSource);
        }
    }
}

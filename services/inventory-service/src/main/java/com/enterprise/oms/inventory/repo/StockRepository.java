package com.enterprise.oms.inventory.repo;

import com.enterprise.oms.inventory.domain.Stock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stock access. The conditional UPDATEs are the concurrency core (contract section 7): the database evaluates the
 * predicate and applies the change atomically under the row lock, so no application-level lock is needed and no
 * read-modify-write window exists. Every statement bumps {@code version} so entity-level optimistic locking elsewhere
 * (admin stock edits) notices the change.
 */
public interface StockRepository extends JpaRepository<Stock, UUID> {

    /** Read-only snapshot used by the optimistic strategy and shortage reporting (never a cached entity). */
    interface Snapshot {
        UUID getProductId();
        int getOnHand();
        int getReserved();
        long getVersion();
    }

    /** Strategy 1 (default): reserved += q iff available >= q. Returns 1 on success, 0 if insufficient. */
    @Modifying(flushAutomatically = true)
    @Query("update Stock s set s.reserved = s.reserved + :q, s.version = s.version + 1, s.updatedAt = :now "
            + "where s.productId = :id and s.onHand - s.reserved >= :q")
    int tryReserve(@Param("id") UUID productId, @Param("q") int quantity, @Param("now") Instant now);

    /** Strategy 3 building block: compare-and-set on version. Returns 0 if the version moved or stock is short. */
    @Modifying(flushAutomatically = true)
    @Query("update Stock s set s.reserved = s.reserved + :q, s.version = s.version + 1, s.updatedAt = :now "
            + "where s.productId = :id and s.version = :version and s.onHand - s.reserved >= :q")
    int compareAndReserve(@Param("id") UUID productId, @Param("q") int quantity, @Param("version") long version,
                          @Param("now") Instant now);

    /** reserved -= q (release / expiry / compensation). 0 means the books are inconsistent. */
    @Modifying(flushAutomatically = true)
    @Query("update Stock s set s.reserved = s.reserved - :q, s.version = s.version + 1, s.updatedAt = :now "
            + "where s.productId = :id and s.reserved >= :q")
    int release(@Param("id") UUID productId, @Param("q") int quantity, @Param("now") Instant now);

    /** on_hand -= q and reserved -= q (the goods left the warehouse). */
    @Modifying(flushAutomatically = true)
    @Query("update Stock s set s.onHand = s.onHand - :q, s.reserved = s.reserved - :q, s.version = s.version + 1, "
            + "s.updatedAt = :now where s.productId = :id and s.reserved >= :q and s.onHand >= :q")
    int ship(@Param("id") UUID productId, @Param("q") int quantity, @Param("now") Instant now);

    @Query("select s.productId as productId, s.onHand as onHand, s.reserved as reserved, s.version as version "
            + "from Stock s where s.productId = :id")
    Optional<Snapshot> snapshot(@Param("id") UUID productId);

    @Query("select s.productId as productId, s.onHand as onHand, s.reserved as reserved, s.version as version "
            + "from Stock s where s.productId in :ids")
    List<Snapshot> snapshots(@Param("ids") Collection<UUID> ids);

    /** Strategy 2: SELECT ... FOR UPDATE (PESSIMISTIC_WRITE). Callers lock in ascending productId order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.productId = :id")
    Optional<Stock> findForUpdate(@Param("id") UUID productId);
}

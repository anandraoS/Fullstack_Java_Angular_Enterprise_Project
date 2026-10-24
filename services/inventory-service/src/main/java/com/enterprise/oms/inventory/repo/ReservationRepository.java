package com.enterprise.oms.inventory.repo;

import com.enterprise.oms.inventory.domain.Reservation;
import com.enterprise.oms.inventory.domain.ReservationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID>, JpaSpecificationExecutor<Reservation> {

    Optional<Reservation> findByOrderId(UUID orderId);

    @Modifying(flushAutomatically = true)
    @Query("update Reservation r set r.status = com.enterprise.oms.inventory.domain.ReservationStatus.FAILED, "
            + "r.expiresAt = null, r.failureReason = :reason, r.failureDetail = :detail, r.updatedAt = :now, "
            + "r.version = r.version + 1 where r.id = :id and r.status = com.enterprise.oms.inventory.domain.ReservationStatus.RESERVED")
    int markFailed(@Param("id") UUID id, @Param("reason") String reason, @Param("detail") String detail,
                   @Param("now") Instant now);

    /** RESERVED -> COMMITTED (TTL cleared). Guarded: loses cleanly against the expiry job. */
    @Modifying(flushAutomatically = true)
    @Query("update Reservation r set r.status = com.enterprise.oms.inventory.domain.ReservationStatus.COMMITTED, "
            + "r.expiresAt = null, r.updatedAt = :now, r.version = r.version + 1 "
            + "where r.id = :id and r.status = com.enterprise.oms.inventory.domain.ReservationStatus.RESERVED")
    int markCommitted(@Param("id") UUID id, @Param("now") Instant now);

    /** from -> RELEASED with a reason. Guarded by the exact source status and "not shipped". */
    @Modifying(flushAutomatically = true)
    @Query("update Reservation r set r.status = com.enterprise.oms.inventory.domain.ReservationStatus.RELEASED, "
            + "r.expiresAt = null, r.releaseReason = :reason, r.updatedAt = :now, r.version = r.version + 1 "
            + "where r.id = :id and r.status = :from and r.shippedAt is null")
    int markReleased(@Param("id") UUID id, @Param("from") ReservationStatus from, @Param("reason") String reason,
                     @Param("now") Instant now);

    /** RESERVED -> EXPIRED, only if still past its TTL (a concurrent commit wins otherwise). */
    @Modifying(flushAutomatically = true)
    @Query("update Reservation r set r.status = com.enterprise.oms.inventory.domain.ReservationStatus.EXPIRED, "
            + "r.expiresAt = null, r.releaseReason = 'EXPIRED', r.updatedAt = :now, r.version = r.version + 1 "
            + "where r.id = :id and r.status = com.enterprise.oms.inventory.domain.ReservationStatus.RESERVED "
            + "and r.expiresAt < :now")
    int markExpired(@Param("id") UUID id, @Param("now") Instant now);

    /** Marks the goods shipped exactly once (shipped_at IS NULL guard = idempotency of FulfillmentCompleted). */
    @Modifying(flushAutomatically = true)
    @Query("update Reservation r set r.shippedAt = :now, r.updatedAt = :now, r.version = r.version + 1 "
            + "where r.id = :id and r.shippedAt is null and r.status in "
            + "(com.enterprise.oms.inventory.domain.ReservationStatus.RESERVED, "
            + "com.enterprise.oms.inventory.domain.ReservationStatus.COMMITTED)")
    int markShipped(@Param("id") UUID id, @Param("now") Instant now);

    @Query("select r.id from Reservation r where r.status = com.enterprise.oms.inventory.domain.ReservationStatus.RESERVED "
            + "and r.expiresAt < :now order by r.expiresAt asc")
    List<UUID> findExpiredIds(@Param("now") Instant now, Pageable pageable);

    long countByStatus(ReservationStatus status);
}

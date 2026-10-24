package com.enterprise.oms.order.repository;

import com.enterprise.oms.order.entity.IdempotencyKeyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyRecord, Long> {

    @Query("select r from IdempotencyKeyRecord r where r.scope = :scope and r.idemKey = :key")
    Optional<IdempotencyKeyRecord> findByScopeAndKey(@Param("scope") String scope, @Param("key") String key);

    @Modifying
    @Query("delete from IdempotencyKeyRecord r where r.createdAt < :before")
    int deleteCreatedBefore(@Param("before") Instant before);
}

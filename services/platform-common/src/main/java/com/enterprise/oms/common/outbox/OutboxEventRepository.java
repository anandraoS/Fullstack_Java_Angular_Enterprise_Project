package com.enterprise.oms.common.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Oldest unpublished rows first (pass a PageRequest of the batch size). */
    @Query("select o from OutboxEvent o where o.publishedAt is null order by o.createdAt asc, o.id asc")
    List<OutboxEvent> findPending(Pageable page);

    long countByPublishedAtIsNull();

    @Modifying
    @Query("delete from OutboxEvent o where o.publishedAt is not null and o.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}

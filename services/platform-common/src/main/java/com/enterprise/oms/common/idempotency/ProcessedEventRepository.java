package com.enterprise.oms.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEvent.Key> {

    long countByIdConsumer(String consumer);

    @Modifying
    @Query("delete from ProcessedEvent p where p.processedAt < :before")
    int deleteProcessedBefore(@Param("before") Instant before);
}

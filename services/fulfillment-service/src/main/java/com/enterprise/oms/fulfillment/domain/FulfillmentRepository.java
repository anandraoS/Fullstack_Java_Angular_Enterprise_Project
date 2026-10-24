package com.enterprise.oms.fulfillment.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FulfillmentRepository extends JpaRepository<Fulfillment, UUID>, JpaSpecificationExecutor<Fulfillment> {

    Optional<Fulfillment> findByOrderId(UUID orderId);

    boolean existsByOrderId(UUID orderId);

    long countByStatusIn(Collection<FulfillmentStatus> statuses);

    @Query("select f.id from Fulfillment f where f.status in :statuses and (f.nextStepAt is null or f.nextStepAt <= :now) "
            + "order by f.nextStepAt asc")
    List<UUID> findDueIds(@Param("statuses") Collection<FulfillmentStatus> statuses, @Param("now") Instant now,
                          Pageable pageable);

    @Query("select f.id from Fulfillment f where f.status in :statuses")
    List<UUID> findIdsByStatusIn(@Param("statuses") Collection<FulfillmentStatus> statuses);
}

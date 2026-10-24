package com.enterprise.oms.order.repository;

import com.enterprise.oms.order.domain.SagaState;
import com.enterprise.oms.order.entity.SagaInstance;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SagaInstanceRepository extends JpaRepository<SagaInstance, UUID> {

    Optional<SagaInstance> findByOrderId(UUID orderId);

    long countByStateIn(Collection<SagaState> states);

    /** Sagas in the given states that made no progress since {@code cutoff}, oldest first. */
    @Query("select s from SagaInstance s where s.state in :states and s.updatedAt < :cutoff order by s.updatedAt asc")
    List<SagaInstance> findStalled(@Param("states") Collection<SagaState> states, @Param("cutoff") Instant cutoff,
                                   Pageable page);

    /** Sagas that know a reservation which was never confirmed released. */
    @Query("select s from SagaInstance s where s.reservationId is not null and s.reservationReleased = false "
            + "and s.state in :states and s.updatedAt < :cutoff order by s.updatedAt asc")
    List<SagaInstance> findWithUnreleasedReservation(@Param("states") Collection<SagaState> states,
                                                     @Param("cutoff") Instant cutoff, Pageable page);
}

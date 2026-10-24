package com.enterprise.oms.inventory.repo;

import com.enterprise.oms.inventory.domain.ReservationItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ReservationItemRepository extends JpaRepository<ReservationItem, UUID> {

    List<ReservationItem> findByReservationIdOrderByProductIdAsc(UUID reservationId);

    List<ReservationItem> findByReservationIdIn(Collection<UUID> reservationIds);
}

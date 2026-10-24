package com.enterprise.oms.inventory.repo;

import com.enterprise.oms.inventory.domain.StockMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    Page<StockMovement> findByProductId(UUID productId, Pageable pageable);

    long countByProductId(UUID productId);
}

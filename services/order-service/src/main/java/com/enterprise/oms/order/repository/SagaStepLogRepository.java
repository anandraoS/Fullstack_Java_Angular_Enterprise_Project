package com.enterprise.oms.order.repository;

import com.enterprise.oms.order.entity.SagaStepLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SagaStepLogRepository extends JpaRepository<SagaStepLog, Long> {

    List<SagaStepLog> findByOrderIdOrderByIdAsc(UUID orderId);
}

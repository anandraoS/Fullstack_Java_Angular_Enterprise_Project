package com.enterprise.oms.order.api.dto;

import com.enterprise.oms.order.domain.SagaState;
import com.enterprise.oms.order.domain.SagaStep;
import com.enterprise.oms.order.domain.StepStatus;
import com.enterprise.oms.order.entity.SagaInstance;
import com.enterprise.oms.order.entity.SagaStepLog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Saga JSON (contract 5.1). */
public record SagaView(UUID orderId, UUID sagaId, SagaState state, SagaStep currentStep, int attempts,
                       Instant startedAt, Instant updatedAt, String lastError, List<Step> steps) {

    public record Step(SagaStep step, StepStatus status, int attempt, String detail, Instant at) {
    }

    public static SagaView of(SagaInstance s, List<SagaStepLog> log) {
        return new SagaView(s.getOrderId(), s.getId(), s.getState(), s.getCurrentStep(), s.getAttempts(),
                s.getStartedAt(), s.getUpdatedAt(), s.getLastError(),
                log.stream().map(l -> new Step(l.getStep(), l.getStatus(), l.getAttempt(), l.getDetail(),
                        l.getOccurredAt())).toList());
    }
}

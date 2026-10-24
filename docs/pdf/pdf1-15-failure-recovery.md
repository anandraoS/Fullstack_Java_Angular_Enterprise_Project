## Failure catalogue

Distributed systems fail in partial, boring ways. The platform assumes every hop can fail and gives each failure window a named, tested mechanism.

| # | Failure | Detection | Automatic recovery | Manual fallback |
|---|---|---|---|---|
| 1 | Process crashes after the DB commit, before publishing | Unpublished rows in `outbox_events`, metric `oms.outbox.pending` | Outbox relay publishes on restart (at-least-once) | Inspect `GET /api/v1/ops/messaging` (`outboxPending`) |
| 2 | Broker unavailable | Relay errors, `attempts` and `last_error` on outbox rows | Relay keeps retrying; service stays up, orders are accepted | Runbook 02 |
| 3 | Duplicate delivery of an event | `oms.events.duplicates` | `processed_events` primary key plus state-machine NO_OP | Runbook 05 |
| 4 | Poison message (bad JSON, validation error) | `oms.events.dead_lettered` | Classified non-retryable, sent straight to `<topic>.DLT` | Inspect DLT headers (`x-exception-*`, original topic, partition, offset), fix, replay |
| 5 | Handler throws transiently (DB blip) | `oms.events.retries` | `DefaultErrorHandler`: 4 attempts, 500 ms doubling to 5 s, then DLT | Replay from DLT |
| 6 | Inventory REST call fails or is slow | Resilience4j metrics, saga step log `FAILED` or `RETRIED` | Retry (3 attempts) and circuit breaker; saga attempt counter | Admin retry endpoint |
| 7 | Saga step never completes (lost reply, consumer down) | Saga older than `oms.saga.stuck-after-seconds` | `SagaRecoveryJob` re-sends the idempotent command; after `oms.saga.max-attempts` order `FAILED` (non-compensable) or saga `NEEDS_ATTENTION` (compensable) | `POST /admin/orders/{id}/retry`, runbook 01 |
| 8 | Order and reservation disagree | `ReconciliationService` queries | Repairs by issuing compensating commands, audits results | `POST /admin/reconciliation/run` |
| 9 | Reservation never confirmed | `expires_at` passed | `ReservationExpiryJob` expires it, releases stock, emits `InventoryReleased{EXPIRED}`; order goes to `FAILED` | None needed |
| 10 | Downstream service down at the edge | Gateway circuit breaker, 503 `UPSTREAM_UNAVAILABLE` | Circuit opens, `Retry-After: 5`, other services unaffected | Runbook 06 |
| 11 | Fulfillment fails | `FulfillmentFailed` after its own 3 attempts | Saga compensates: release inventory, order `FAILED` | Admin fulfillment retry |
| 12 | Notification provider down | `notifications.status` `RETRYING` or `FAILED` | Backoff 1 s doubling to 30 s, 5 attempts | Admin notification retry |

## Why these mechanisms are sufficient and where they stop

* **Outbox** closes the dual-write hole (state committed but event lost). It does not give exactly-once delivery: duplicates are possible and are handled downstream.
* **Idempotent consumers** make duplicates harmless for the business effect. The `processed_events` insert shares the transaction with the effect, so a crash between the two cannot record "processed" without applying the effect.
* **Recovery and reconciliation** handle failures that no retry can see, such as a reply that was never sent. They are periodic jobs and therefore bounded by their interval.
* **Compensation is semantic**: releasing a reservation does not undo history; the audit trail shows both the reservation and the release.
* Kafka replication factor is 1 locally and 3 in the production design; the local topology is not highly available.

## Key code

{{code:services/platform-common/src/main/java/com/enterprise/oms/common/outbox/OutboxRelay.java|public class OutboxRelay|40|java}}

## Order of operations for an on-call engineer

1. Is the service ready? (`/actuator/health/readiness`, gateway `GET /api/v1/ops/health`.)
2. Is the broker reachable and is lag growing? (`/api/v1/ops/messaging`, Prometheus.)
3. Is anything in a DLT? Read the exception headers before replaying.
4. Which orders are stuck? (`/admin/orders/failed`, then `/admin/saga/{orderId}` for the step log.)
5. Follow the matching runbook and finish with a post-incident review (`docs/governance/post-incident-review-template.md`).

# Performance checklist

Load when: list endpoints, queries, consumers, caches, or k6 scripts change. Runbooks: `docs/runbooks/04`, `08`.

## Database
- [ ] Each list endpoint: bounded page (max 100), indexed filter/sort columns, `COUNT` cost considered.
- [ ] No N+1: statement count independent of page size (batch `IN`, join fetch to-one).
- [ ] Reservation path: single conditional `UPDATE` per line; rows touched in sorted productId order; tx has no remote calls.
- [ ] Hikari pool sized to (cores x 2 + spindles) per pod, `connectionTimeout` short; watch `hikaricp.connections.pending`.
- [ ] `EXPLAIN (ANALYZE, BUFFERS)` attached for any new query on tables expected above 100k rows.

## Messaging
- [ ] Partition key `orderId`; consumer concurrency <= partitions (3).
- [ ] Batch size, `max.poll.interval.ms` > worst-case processing; no blocking HTTP in listeners without timeout.
- [ ] Outbox relay batch size and poll interval set; `oms.outbox.pending` alert threshold defined.
- [ ] Retry/backoff bounded (4 attempts, 500ms x2 to 5s) so one poison record cannot stall a partition for minutes.

## HTTP and resilience
- [ ] Timeouts: connect 2s, read 3s outbound; gateway 10s. Circuit breaker around order->inventory validation.
- [ ] Response size bounded; compress only above a threshold; no entity graphs serialized.
- [ ] Rate limiting configured (Redis bucket in prod, in-memory locally).

## JVM
- [ ] Container-aware heap (`-XX:MaxRAMPercentage=70`), no unbounded in-memory caches (Caffeine with max size and TTL).
- [ ] No per-request large allocations (no `findAll()`); streaming for exports.
- [ ] Metrics reviewed: `jvm.memory.used`, `jvm.gc.pause`, `http.server.requests` p95/p99, `oms.order.processing.duration`.

## Frontend
- [ ] Lists paginated server-side; `track` in `@for`; OnPush; polling backs off and stops on terminal state.
- [ ] Bundle budgets respected (`ng build` warns); lazy-load feature routes.

## Evidence
Performance claims need numbers from `performance/` (k6) or a profile. If the number was not measured, say "not measured".

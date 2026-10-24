# Sprint Plan - OMS (4 sprints x 2 weeks)

Delivery status (2026-10-18): the plan below is the original forward plan and is kept as written; all eight modules are now implemented and integration-tested locally (see the status table after the sprint overview and `docs/validation/validation-report.md`). Baseline (Sprint 0, on disk before the plan): `services/platform-common`, `platform-test-support`, `local-stack`, Angular UI `frontend/oms-ui` (18 spec files), Playwright suite against a mock backend, Compose/Kubernetes/Terraform/CI assets (statically validated only). Contract: `docs/architecture/00-shared-contract.md`.

Planning assumptions (change them if the team differs):
* Team: 4 engineers (2 backend, 1 full-stack/frontend, 1 platform/QA), 10 working days per sprint, focus factor 0.7 => about 224 productive hours, about 40 story points per sprint.
* Estimation scale: Fibonacci points; **1 point is roughly 5 hours** of focused work including tests and review. Hours below are the sum of task hours and may differ slightly from points x 5 (rounding).
* Docker is NOT available on the dev machine: any story whose acceptance needs PostgreSQL via Testcontainers is verified in CI (Linux runner with Docker) and is explicitly "CI-verified, not locally verified" until a Docker host is available.
* Definition of Ready / Done: `definition-of-ready-and-done.md`. Reviews: `code-review-checklist.md`.

## Sprint overview

| Sprint | Goal | Points | Hours |
|---|---|---|---|
| 1 | Order slice: customer/order API, state machine, outbox events, idempotent create, inventory validate + reserve | 40 | 200 |
| 2 | Saga complete: fulfillment, notification, compensation, recovery job, reconciliation, ops endpoints | 40 | 200 |
| 3 | Edge and experience: gateway, authz end-to-end, frontend on real backends, e2e on real stack, observability | 38 | 190 |
| 4 | Production readiness: Postgres path verification, security hardening, performance, deployment dry-run, DR/rollback drills, docs | 36 | 180 |

### Delivery status against the plan (2026-10-18)

Evidence: `docs/validation/validation-report.md`, `backend-test-summary.md`, `integration-defects.md`. "Done" means implemented and verified on the local no-Docker stack (H2 in PostgreSQL mode, embedded KRaft Kafka); nothing was executed on Docker, a cluster, PostgreSQL or k6.

| Epic / story | State | Real artefacts | Still open |
|---|---|---|---|
| E1 order service (S1.x) | done | `order/api/OrderController`, `CustomerController`, `AdminController`; `OrderStateMachine`; `OrderCommandService`; idempotency (`IdempotencyKeyRecord`, `IdempotencyKeyCleanupJob`); tests `OrderApiTest`, `IdempotencyApiTest`, `OrderStateMachineTest` | none beyond limitations |
| E2 inventory (S2.x) | done | `ReservationService` with `AtomicUpdate`/`PessimisticLock`/`OptimisticVersion` strategies, `ReservationExpiryJob`, `ProductService`, `InventoryController`/`InternalController`; `ReservationConcurrencyTest` | row locking never run on PostgreSQL |
| E3 validation hop | done | `saga/InventoryClient`, `RestInventoryClient`, `InventoryClientResilienceTest` | OIDC mode has no SERVICE token (limitation 6) |
| E4 saga (S4.1-S4.3) | done | `OrderSagaOrchestrator`, `SagaRecoveryJob`, `ReconciliationService`; `OrderSagaKafkaIT`, `SagaRecoveryTest`, `ReconciliationTest` | scheduled jobs do not claim rows (limitation 1) |
| E5 fulfillment and notification | done | `FulfillmentService`, `FulfillmentProgressJob`; `NotificationService`, `NotificationDeliveryJob`, `BackoffPolicy` | none beyond limitations |
| E6 ops endpoints | done | `AdminController` (`/admin/orders/failed`, retry, `/admin/saga/{orderId}`, `/admin/reconciliation/run`), `OpsMessagingController`, `OpsAuditController` | no DLT replay tool (limitation 3) |
| E7 gateway | done | `EdgeSecurityConfig`, `RateLimitWebFilter`, `TokenController`, `OpsHealthController`, `RoutesConfig`; 15 test suites | OIDC/JWKS untested |
| E8 UI on real stack | done | Playwright live 18/18, mock 18/18; frontend 149/149 | not wired in `ci.yml` |
| E9 observability | partly | 33 metric names in dashboards/alerts matched a live scrape, 0 missing | no Prometheus/Grafana/OTel/Tempo ran; traceparent not continuous across Kafka |
| S10.1 PostgreSQL ITs | written, not run | `*PostgresIT` (28 skipped tests) | needs a Docker host or CI |
| S10.2 security test pack | mostly done | `SecurityApiTest`, `OrderSecurityTest`, `EdgeSecurityTest` | producer-ownership validation of events and per-service log-leak scan not implemented |
| S10.3 dependency hygiene | not done | scanners defined in `ci.yml`, never run | pin Actions to SHA, digest-pin images |
| S10.4 retention | done (H2 test only) | `platform-common` `RetentionPurgeJob` (`oms.retention.*`: `processed_events` 14 d, `audit_events` 365 d), `RetentionPurgeJobTest` | not tested at scale |
| S11.1 k6 baseline | not done | `performance/order-flow.js`, `oversell.js`, `lib/common.js` (syntax-checked only) | k6 never run, no latency numbers |
| S11.2 deploy and rollback rehearsal | not done | manifests rendered with kustomize + kubeconform | no cluster |
| S11.3 KEDA/HPA tuning | not done | example under `infrastructure/kubernetes/keda` | not in any overlay; HPA 2-6 exceeds 3 partitions |
| S11.4 docs, ADRs, runbooks, specs | done | `specs/openapi` (5 files), `specs/events` (15 schemas + envelope), `tests/api-collection`, `tests/bdd`, ADRs, runbooks, `docs/_todo-link-check.md` resolved | see `docs/known-limitations.md` |

The original estimate risk (R-20) is closed: all modules were delivered; actual velocity was not measured against the plan.

---

## Sprint 1 - Order and inventory core (weeks 1-2)

**Sprint goal:** A customer can create an order idempotently; the order is validated against inventory and stock is reserved atomically; every state change writes history, audit and an outbox event in one transaction.
**Demo:** run `local-stack` + order-service + inventory-service; `POST /api/v1/orders` twice with the same key (replay shows `Idempotent-Replay: true`); order SKU-1019 qty 5 (stock 3) -> REJECTED; show `/api/v1/ops/messaging` and `/api/v1/ops/audit`.

### Epic E1 - Order service foundation (implemented: `services/order-service`)

| Story | Pts | Hrs | Tasks (hrs) | Acceptance criteria | Depends on |
|---|---|---|---|---|---|
| S1.1 Service skeleton wired to platform-common | 3 | 15 | pom + reactor profile activation (3), Boot app + profile config (4), Flyway V10 customers/orders/order_items (6), boot test with H2 (2) | Service starts on 18081 with `OMS_JWT_SECRET` set and FAILS to start without it; Flyway runs `classpath:db/common` + `db/migration`; `/actuator/health/readiness` UP | platform-common (done) |
| S1.2 `OrderStateMachine` pure domain class | 5 | 25 | transition table (4), exceptions mapping (2), exhaustive unit test of all 9x9 state pairs (10), idempotent re-apply tests (4), review (5) | Every valid pair in contract section 6 passes; every other pair throws `InvalidStateTransitionException`; 100 percent branch coverage on the class (JaCoCo) | S1.1 |
| S1.3 Create order with `Idempotency-Key` | 8 | 40 | `idempotency_keys` migration (3), controller + DTO validation (items 1-50, qty 1-1000, no duplicate productId) (8), fingerprint/replay logic using `IdempotencyKeys` (8), single-tx write order+history+saga+outbox+audit (10), concurrency race test (6), problem+json assertions (5) | 202 + Location; replay 202 + `Idempotent-Replay: true`; same key different body 422 `IDEMPOTENCY_KEY_REUSED`; missing 400 `IDEMPOTENCY_KEY_REQUIRED`; malformed 400 `VALIDATION_FAILED`; 20 parallel identical requests create exactly 1 order | S1.2 |
| S1.4 Order read APIs with ownership | 5 | 25 | GET by id/list/history (8), `CurrentUser.canAccessCustomer` enforcement (4), forced customer filter on list (4), `PageRequestFactory` whitelist (3), tests (6) | CUSTOMER sees own orders only; other customer's order -> 404 (not 403); size 101 -> 400; unknown sort field -> 400 | S1.3 |

### Epic E2 - Inventory service (implemented: `services/inventory-service`)

| Story | Pts | Hrs | Tasks (hrs) | Acceptance criteria | Depends on |
|---|---|---|---|---|---|
| S2.1 Catalog, stock tables, seed | 5 | 25 | V10 products/stock/reservations/movements (7), seed V1000 with 20 SKUs (3), product read API (8), role-shaped JSON (customers see `available` only) (7) | SKU-1020 stock 0, SKU-1019 stock 3, SKU-1001 stock 500 in dev profile; CUSTOMER response contains no `onHand`/`reserved` | platform-common |
| S2.2 Internal validate endpoint | 3 | 15 | `/api/v1/internal/products/validate` (6), SERVICE-role guard (3), tests (6) | CUSTOMER/OPERATOR token -> 403; SERVICE token -> 200; inactive product -> `ok=false` | S2.1 |
| S2.3 Atomic reservation via commands | 8 | 40 | Kafka listener for `ReserveInventory` through `IdempotentEventProcessor` (10), conditional UPDATE, sorted-id order (8), reservation + outbox events (8), duplicate-command no-op (4), concurrency test with H2 + threads (6), CI-only PG variant on `AbstractPostgresIT` (4) | Concurrent reservations of qty 3 against stock 5 never oversell; all-or-nothing across lines; duplicate command returns original outcome | S2.1, S1.3 |

### Epic E3 - Validation hop

| Story | Pts | Hrs | Tasks | Acceptance criteria | Depends on |
|---|---|---|---|---|---|
| S3.1 Order -> inventory REST validation | 3 | 15 | `OmsRestClientFactory` client, SERVICE token via `ServiceTokenProvider` (4), Resilience4j CB + 3 retries (6), mapping to VALIDATED/REJECTED (5) | Inventory down: order stays CREATED, retries counted, no 500 to caller; invalid product -> REJECTED with reason | S2.2, S1.2 |

Sprint 1 total: 3+5+8+5+5+3+8+3 = 40 points; hours 200.
**Risks this sprint:** R-01, R-04, R-09. **Carry-over policy:** S2.3 PG variant may slip to Sprint 4 if Docker host unavailable.

---

## Sprint 2 - Saga, fulfillment, notification (weeks 3-4)

**Sprint goal:** Orders flow end to end across four services with compensation and self-healing; operators can inspect and retry.
**Demo:** order reaches FULFILLED; order with city FAILTOWN ends FAILED with released stock; kill and restart order-service mid-saga and show recovery; run reconciliation; show DLT record via `/api/v1/ops/messaging`.

### Epic E4 - Orchestrated saga (implemented: `OrderSagaOrchestrator`, `SagaRecoveryJob`, `ReconciliationService` in `order/saga/`)

| Story | Pts | Hrs | Tasks (hrs) | Acceptance criteria | Depends on |
|---|---|---|---|---|---|
| S4.1 `OrderSagaOrchestrator` | 8 | 40 | saga tables V1x (4), step handlers VALIDATE/RESERVE/CONFIRM/FULFILL (14), listeners for inventory + fulfillment events with `processOnce` (10), step log + audit (4), tests (8) | Happy path to FULFILLED; `InventoryReservationFailed` -> REJECTED; each transition writes history + saga_step_log + audit + outbox in one tx; duplicate event is a no-op | S1.2, S2.3 |
| S4.2 Compensation and cancel | 5 | 25 | cancel endpoint (5), `ReleaseInventory` command + `InventoryReleased` handling (8), cancel-before-reservation-arrives case (6), tests (6) | Cancel from CREATED/VALIDATED/INVENTORY_RESERVED/CONFIRMED allowed; from terminal 409; late reservation after cancel is released; order stays CANCELLED | S4.1 |
| S4.3 `SagaRecoveryJob` | 5 | 25 | scheduled finder (6), idempotent re-drive (6), max-attempts to FAILED/flag + metric `oms.saga.compensations` (5), tests with small thresholds (8) | Stuck saga re-driven after `oms.saga.stuck-after-seconds`; after `oms.saga.max-attempts` FAILED or COMPENSATION_PENDING flagged; re-drive twice yields one business effect | S4.1 |
| S4.4 `ReconciliationService` + admin endpoint | 3 | 15 | 3 inconsistency queries (6), repair via commands + audit (5), `POST /admin/reconciliation/run` ADMIN only (4) | OPERATOR gets 403; ADMIN gets `{examined, repaired, details[]}`; results appear in audit | S4.2 |

### Epic E5 - Fulfillment and notification services (implemented: `services/fulfillment-service`, `services/notification-service`)

| Story | Pts | Hrs | Tasks | Acceptance criteria | Depends on |
|---|---|---|---|---|---|
| S5.1 Fulfillment simulation | 5 | 25 | tables (4), `StartFulfillment` consumer (6), scheduler PICKING->COMPLETED (6), FAILTOWN + failure-rate hook (4), retry admin endpoint (5) | Steps advance with `oms.fulfillment.step-delay-ms`; FAILTOWN emits `FulfillmentFailed` after own retries; duplicate command no second fulfillment | S4.1 |
| S5.2 Notification consumer | 3 | 15 | tables with UNIQUE (source_event_id, template) (4), consumer (4), simulated failure `fail.example.test` + backoff (4), role-filtered list API (3) | Duplicate event -> single notification; CUSTOMER sees own only; no email logged unmasked (`LogMasking`) | S4.1 |
| S5.3 Inventory consumes fulfillment/order events | 3 | 15 | commit on `OrderConfirmed`, ship on `FulfillmentCompleted`, expiry scheduler (15) | `on_hand -= q; reserved -= q` exactly once; expired reservation emits `InventoryReleased{EXPIRED}` and order -> FAILED | S2.3 |

### Epic E6 - Ops endpoints

| Story | Pts | Hrs | Tasks | Acceptance criteria | Depends on |
|---|---|---|---|---|---|
| S6.1 Admin failed-orders, retry, saga view; messaging in each service | 5 | 25 | endpoints (12), `oms.messaging.topics` config per service (5), tests (8) | `GET /admin/orders/failed` lists REJECTED/FAILED/stuck COMPENSATION_PENDING; retry is idempotent; `/ops/messaging` returns `lagAvailable:false` rather than failing when Kafka admin is unreachable | S4.1 |

Sprint 2 total: S4.1 8 + S4.2 5 + S4.3 5 + S4.4 3 + S5.1 5 + S5.2 3 + S5.3 3 + S6.1 5 = 37 points (185 h), plus 3 points (15 h) reserved for Sprint 1 spillover and defects = 40 points; 200 h.
**Dependencies:** Sprint 1 S2.3 and S1.2 must be Done. **Risks:** R-02, R-03, R-05, R-10.

---

## Sprint 3 - Gateway, security end to end, UI on real backends (weeks 5-6)

**Sprint goal:** A browser user logs in, orders and tracks through the real gateway; authorization holds at every layer; telemetry is visible.
**Demo:** `scripts/run-local.sh` starts everything; Playwright suite passes against the real stack; Grafana "OMS - API RED" shows traffic; customer2 requesting customer1's order gets 404.

### Epic E7 - API gateway (implemented: `gateway/`)

| Story | Pts | Hrs | Tasks (hrs) | Acceptance criteria | Depends on |
|---|---|---|---|---|---|
| S7.1 Routes + edge JWT validation | 5 | 25 | route table per contract (6), JWT resource server with issuer/audience (6), `/api/v1/internal/**` not routed (3), tests (10) | Missing/invalid token -> 401 problem+json; `/api/v1/internal/products/validate` through gateway -> 404; correlation id injected and echoed | Sprint 2 services |
| S7.2 Dev token endpoint (profile `dev` only) | 3 | 15 | endpoint (5), users from env `OMS_DEMO_PASSWORD` (4), profile guard test: endpoint absent without `dev` (6) | No default password in code (grep gate in CI); failure returns generic 401 | S7.1 |
| S7.3 Rate limiting with in-memory fallback | 5 | 25 | bucket config (8), Redis impl + `oms.redis.enabled=false` fallback (8), 429 `RATE_LIMITED` with `Retry-After` (4), tests (5) | Burst over limit gets 429; works with Redis disabled | S7.1 |
| S7.4 Ops health aggregate + ops route rewrite | 3 | 15 | `GET /api/v1/ops/health` always 200 (8), rewrite `/ops/{svc}/**` (7) | One service down reported in body as DOWN, HTTP 200 | S7.1 |

### Epic E8 - Frontend and e2e on real stack

| Story | Pts | Hrs | Tasks | Acceptance criteria | Depends on |
|---|---|---|---|---|---|
| S8.1 Point UI at gateway, remove mock reliance | 5 | 25 | proxy `/api` -> 18080 (2), contract response-shape audit (10), fix mismatches (8), spec updates (5) | UI works for all screens against real services | S7.1 |
| S8.2 Playwright against real stack | 8 | 40 | run-local harness (10), data reset strategy (8), port the 15 tests in `tests/e2e/specs/order-lifecycle.spec.ts` (14), flake triage (8) | 15/15 pass on real stack three consecutive runs; mock backend kept only for UI-only runs | S8.1 |

### Epic E9 - Observability

| Story | Pts | Hrs | Tasks | Acceptance criteria | Depends on |
|---|---|---|---|---|---|
| S9.1 Metrics and logs per contract | 5 | 25 | `oms.*` metrics emitted from services (10), JSON logs fields (5), run `scripts/check-observability.py` (5), fix gaps (5) | Dashboards in `observability/grafana/dashboards` populate; every log line has `correlationId`; no unmasked email in logs (log-scan test) | Sprint 2 |

Sprint 3 total: 5+3+5+3+5+8+5 = 34, + 4 points for Sprint 2 spillover = 38 points; hours 190.
**Risks:** R-06, R-07, R-08, R-13.

---

## Sprint 4 - Production readiness (weeks 7-8)

**Sprint goal:** Evidence, not assertion: PostgreSQL path verified in CI, security tests complete, load baseline recorded, deploy and rollback rehearsed on a real cluster.
**Demo:** CI green including Testcontainers jobs; k6 report; `kubectl rollout undo` rehearsal on a test cluster; threat-model test matrix all green.

### Epic E10 - Verification and hardening

| Story | Pts | Hrs | Tasks | Acceptance criteria | Depends on |
|---|---|---|---|---|---|
| S10.1 Run PostgreSQL ITs in CI | 5 | 25 | enable failsafe `verify` wiring (6), `AbstractPostgresIT` suites for each service (14), fix portability bugs (5) | `PostgresContainerSmokeTest` and per-service PG ITs run (not skipped) on the CI runner; Flyway V1..V1000 apply on PG 16 | Sprint 2 |
| S10.2 Security test pack (threat-model tests) | 8 | 40 | tests mapped in `docs/security/threat-model.md` section 5: cross-customer, privilege escalation, forged JWT/event, SQLi probes, log leakage, payload size (30), triage (10) | All previously pending tests in the threat model either implemented and green or risk re-accepted in writing (status: mostly done, residual items listed in `docs/known-limitations.md`) | Sprints 1-3 |
| S10.3 Dependency and image hygiene | 3 | 15 | review Trivy/Semgrep/gitleaks findings (6), pin Actions to SHA (4), digest-pin base images (5) | No fixable HIGH/CRITICAL; all `uses:` pinned by SHA | CI exists |
| S10.4 processed_events / audit_events retention | 3 | 15 | purge jobs with configurable retention, modelled on the existing outbox cleanup (`OutboxProperties.retention`) (8), tests (7) | Rows older than retention removed; outbox cleanup behaviour confirmed unchanged (published only, never unpublished); audit retention set by policy | Sprint 2 |

### Epic E11 - Performance and operability

| Story | Pts | Hrs | Tasks | Acceptance criteria | Depends on |
|---|---|---|---|---|---|
| S11.1 k6 load baseline | 5 | 25 | scenarios in `performance/` (10), run on test cluster (8), record p95/p99 + lag (7) | Report committed; NFR-02/NFR-03 either met or variance documented | S10.1 |
| S11.2 Deploy + rollback rehearsal | 5 | 25 | apply overlay dev/test to a real cluster (10), expand/contract migration drill (8), `rollout undo` drill (7) | Rehearsal log committed; probes and NetworkPolicies confirmed working or fixed | cluster access |
| S11.3 KEDA and HPA tuning | 2 | 10 | lag-based scaling trial (10) | Decision recorded (HPA vs KEDA per consumer) | S11.2 |
| S11.4 Docs, ADRs, runbooks, API specs | 3 | 15 | export OpenAPI to `specs/openapi`, JSON Schemas to `specs/events`, ADRs, runbooks (15) | `docs/_todo-link-check.md` has no unresolved entries (met 2026-10-18; open items live in `docs/known-limitations.md`) | all |

Sprint 4 total: 5+8+3+3+5+5+2+3 = 34, + 2 buffer = 36 points; hours 180.
**Risks:** R-11, R-12, R-14, R-15.

---

## Cross-sprint dependency chain

```
platform-common (done) -> S1.1 -> S1.2 -> S1.3 -> S4.1 -> S4.2/S4.3/S4.4
                      \-> S2.1 -> S2.2 -> S3.1
                                 \-> S2.3 -> S4.1
S4.1 -> S5.1, S5.2 ;  Sprint 2 services -> S7.1 -> S8.1 -> S8.2 ;  S10.1 -> S11.1 -> S11.2
```

## Sprint ceremonies and exit gates

* Planning day 1, daily 15 min, review/demo last day, retrospective after review (blameless; actions tracked like `post-incident-review-template.md`).
* Gate per sprint: all stories meet the Definition of Done; honest status table updated (what is verified locally, what only in CI, what is statically validated).

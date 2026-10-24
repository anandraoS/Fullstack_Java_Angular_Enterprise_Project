# Test Strategy - OMS

Status (refreshed 2026-10-18 against the code and `docs/validation/`): all eight modules are implemented and tested. The full reactor `mvn verify` ran green: **968 test cases (907 unit + 61 integration), 939 executed, 939 passed, 0 failed, 29 skipped** (28 PostgreSQL Testcontainers tests without Docker, 1 opt-in benchmark); details in `docs/validation/backend-test-summary.md`. Frontend 149/149. Playwright live (UI -> gateway -> real services) 18/18 and mock 18/18. What was never run is listed in section 2 and in `docs/known-limitations.md`. Class names below were found by globbing `services/**/src/test`, `gateway/src/test`, `tests/` and `frontend/oms-ui/src`.

## 1. Principles

1. Backend is authoritative: every security and business rule is proven at API/service level; UI tests prove UX only.
2. Test behaviour at the cheapest level that can prove it (pyramid below).
3. Deterministic: no `Thread.sleep` (use `Awaits`), runtime-generated secrets (`JwtTestTokens`, `JwtSecret.random()`), no network beyond embedded/loopback.
4. Honest reporting: a skipped test is not a passed test. State what ran, what was skipped and why in each PR and sprint demo.

## 2. Honest environment matrix

| Capability | Dev machine (Windows, no Docker) | CI (`.github/workflows/ci.yml`, ubuntu-latest, never executed yet; the reactor was run locally instead) |
|---|---|---|
| Unit tests (JUnit 5, surefire) | runs | `java-build-test` job |
| Spring slice/API tests on H2 (PostgreSQL mode) | runs | runs |
| Embedded Kafka (spring-kafka-test KRaft) via `AbstractKafkaIT` | runs | runs |
| Testcontainers PostgreSQL (`AbstractPostgresIT`, `@Testcontainers(disabledWithoutDocker = true)`) | **skipped**, not failed | `java-integration` job (`mvn verify`, failsafe `*IT`) - assumption: failsafe wiring in `services/pom.xml` must be confirmed |
| Angular unit tests (Karma + Chrome headless) | runs | `frontend` job |
| React unit and component tests (Vitest + Testing Library + MSW, jsdom) | **ran 207/207 on 2026-10-19**, coverage 96.3 % statements | React frontend job in `ci.yml` (never executed on a runner) |
| React image build, compose profile `react`, Kubernetes `frontend-react.yaml` apply | **never run** (no Docker, no cluster) | not executed |
| Playwright e2e, mock mode (default: `tests/e2e/mock-backend/server.js` serves UI dist + fake API on port 4300) | runs | not wired in ci.yml |
| Playwright e2e, live mode (`E2E_BASE_URL` + `E2E_DEMO_PASSWORD`) | **ran 18/18 on 2026-10-18** against the local no-Docker stack (`scripts/run-local.sh --ui`) | not wired in ci.yml; `smoke-local` job runs `scripts/smoke-test.*`, not Playwright |
| k6 (`performance/order-flow.js`, `oversell.js`) | **never run** (no k6 binary; only `node --check`); the live substitute is `tests/e2e/oversell-live.mjs` (30 orders, stock 3: 3 FULFILLED, 27 REJECTED) | not wired |
| kustomize/kubeconform/terraform validate (`scripts/validate-infra.sh`) | where tools are fetched | `infra-validate` job |
| Real cluster rollout, NetworkPolicy enforcement, HPA/KEDA | **not tested anywhere yet** | `deploy-test` is a PLACEHOLDER |

## 3. Pyramid mapped to actual tests

```
                  /\            E2E / live smoke        few, slow, real stack (live run done: Playwright 18/18, live-flows 54/54, smoke 10/10)
                 /  \           Playwright (18 tests, mock and live), smoke-test.mjs, live-flows.mjs
                /----\          Contract / API          service API tests with MockMvc/WebTestClient (all services and gateway)
               /      \         Integration             Kafka embedded (runs), PostgreSQL Testcontainers (skipped locally)
              /--------\        Unit                    pure logic, filters, helpers, Angular specs (149) and React Vitest tests (207), run locally
```

### 3.1 Unit and slice tests (backend, exist)

`services/platform-common/src/test/java/com/enterprise/oms/common/`

| Test class | Methods | What it protects |
|---|---|---|
| `security/JwtSecretTest` | 6 (fail-fast absent/short, env read, exactly 32 bytes, ephemeral only when allowed, app startup fails without/with short secret) | Secret handling |
| `security/SecurityApiTest` | 16 (valid token; missing/garbage/expired/wrong audience/wrong issuer/other-secret/tampered/`alg:none` -> 401; method security 403; unknown roles ignored; internal endpoints need SERVICE; `CurrentUser` contents; security headers + correlation echo; health/api-docs public; ops messaging degrades) | AuthN, role checks, token forgery |
| `error/ExceptionHandlerApiTest` | 9 | problem+json shape for the error codes |
| `web/WebFiltersTest` | 5 (correlation echo/generate, 413 on Content-Length, within-limit pass, chunked counted) | Correlation id, payload limit |
| `web/PageRequestFactoryTest` | 4 (defaults, whitelisted sort, rejects oversize/negative/unknown sort, Idempotency-Key validation) | Paging, sort-injection guard, idempotency key format |
| `observability/LogMaskingTest` | 3 (emails, tokens/secrets in free text, newlines) | Log hygiene |
| `events/EventEnvelopeTest` | 4 | Envelope serialization, tolerant reader |
| `idempotency/IdempotentProcessorAndAuditTest` | 4 (duplicate event, once per consumer, rollback of marker on failure, audit actor + paging) | Idempotent consumer and audit |
| `outbox/OutboxRelayResilienceTest` | 3 (Kafka down then recovered keeps order, unreachable broker keeps row pending, batch limit) | Outbox resilience |

Note: `IdempotencyKeys` is covered by `PageRequestFactoryTest.idempotencyKeyValidation` (it lives in that class, not in a dedicated test).

`services/platform-test-support/src/test/.../testsupport/`: `HelpersTest` (3), `KafkaSupportTest` (1, embedded Kafka), `PostgresContainerSmokeTest` (1, **skipped without Docker**).
`services/local-stack/src/test/.../localstack/LocalKafkaBrokerTest` (3, embedded broker).

### 3.2 Integration tests with embedded Kafka (exist, run locally)

* `messaging/ConsumerKafkaTest` (6): process once and count duplicates with correlation in MDC; poison message retried with backoff then DLT; malformed JSON to DLT without retries and without blocking the partition; unknown event type ignored and counted; ops status reports lag from AdminClient.
* `outbox/OutboxRelayKafkaTest` (3): published with key and headers and marked published; append requires an active transaction; rolled-back transaction leaves no row.

### 3.2a Service and gateway tests (exist; counts are `@Test` methods found by grep, the reactor total is in `backend-test-summary.md`)

| Module | Test classes | Notes |
|---|---|---|
| `gateway/src/test/java/com/enterprise/oms/gateway/` | `EdgeSecurityTest` (13: missing/garbage/expired/other-secret/wrong aud/iss/`alg:none`/wrong HMAC alg/no exp or sub/token in query ignored/health open), `RoutingAndHeadersTest` (route families, ops rewrite, `/api/v1/internal/**` never routed even with SERVICE or ADMIN token, traversal rejected, header forwarding/stripping, correlation id, 413), `RateLimitTest` (3), `ratelimit/InMemoryTokenBucketLimiterTest`, `BulkheadConcurrencyTest` (1; shown to fail with the old 25-call limit), `ResilienceTest`, `CorsTest`, `TokenEndpointTest`, `TokenThrottleTest`, `auth/LoginThrottleTest`, `OpsHealthTest`, `OpsHealthUnreachableTest`, `StartupConfigurationTest`, `NonDevProfileTest` (no token endpoint outside `dev`), `filter/EdgeFiltersUnitTest` | 15 suites, 142 tests, reactive; stub upstreams via `StubServer`; no Docker |
| `services/order-service/src/test/.../order/` | `domain/OrderStateMachineTest` (13), `api/OrderApiTest` (35), `api/IdempotencyApiTest` (11), `api/OrderSecurityTest` (10), `api/AdminApiTest` (8), `api/CustomerApiTest` (6), `api/RateLimitApiTest` (5), `api/OperationalEndpointsTest` (10), `saga/OrderSagaOrchestratorTest` (23), `saga/SagaOrchestrationTest` (23), `saga/SagaRecoveryTest` (12), `saga/ReconciliationTest` (5), `saga/InventoryClientResilienceTest` (11), `service/OrderCommandServiceTest` (16), `service/OrderQueryServiceTest` (7), `service/OrderTransitionsLoggingTest`, `persistence/OrderRepositoryTest` (8, includes an N+1 statement-count test), `persistence/SeedMigrationTest`, `ratelimit/*Test`, `bdd/OrderBddScenariosTest` (15), `kafka/OrderSagaKafkaIT` (12), `kafka/OrderResilienceKafkaIT` (8) | 24 unit suites (436 tests) + 3 IT suites (27, 7 skipped); embedded KRaft Kafka and H2 |
| `services/order-service` PostgreSQL | `persistence/OrderSearchPostgresIT` (7, EXPLAIN on search queries) | **skipped without Docker, never run** |
| `services/inventory-service/src/test/.../inventory/` | `InventoryApiTest` (15), `ProductApiTest` (16), `ReservationServiceTest` (10), `reservation/ReservationStrategyUnitTest` (10), `service/ReservationServiceUnitTest` (6), `ReservationConcurrencyTest` (5), `ReservationLifecycleTest` (13: release, expiry, commit-vs-expiry race), `InventoryKafkaIT` (14), `InventoryDbTest`, `ProductCacheTest`, `SeedDataTest`, `InventoryBddScenariosTest` (28), `ActuatorAndDocsTest`, `ProdProfileDocsDisabledTest` | concurrency runs on H2 (does not prove PostgreSQL isolation behaviour) |
| `services/inventory-service` PostgreSQL | `InventoryPostgresIT`, `InventoryLocalPostgresIT` (9 + 9 executions), `StrategyBenchmarkTest` (opt-in) | **skipped**, never run |
| `services/fulfillment-service/src/test/.../fulfillment/` | `FulfillmentApiTest`, `FulfillmentServiceTest`, `FulfillmentStateMachineTest`, `FulfillmentKafkaTest` (`@EmbeddedKafka`), `FulfillmentBddScenariosTest`, `ProdProfileDocsDisabledTest`; `FulfillmentPostgresIT` skipped | 44 unit + 1 IT |
| `services/notification-service/src/test/.../notification/` | `NotificationApiTest` (10), `NotificationServiceTest` (11), `NotificationUnitTest` (15), `NotificationKafkaTest` (10), `NotificationBddScenariosTest` (4), `ProdProfileDocsDisabledTest`; `NotificationPostgresIT` skipped | 60 unit + 1 IT |
| `services/platform-common/src/test/.../common/` | adds `retention/RetentionPurgeJobTest` (2, H2), `messaging/MessagingMetricsTest`, `observability/PlatformDefaultsTest`, `outbox/OutboxRelayResilienceTest`, `outbox/OutboxRelayKafkaTest`, `messaging/ConsumerKafkaTest` | 66 tests in the reactor |

BDD: `tests/bdd/*.feature` (order, inventory, fulfillment, notification scenarios) are mirrored as JUnit in the `*BddScenariosTest` classes of each service (no Cucumber engine runs the `.feature` files).

### 3.3 First-draft planned classes versus what exists

| First-draft planned class | What exists now | Status |
|---|---|---|
| `OrderStateMachineTest` | `order/domain/OrderStateMachineTest` (13) | done |
| `OrderApiTest` / `CreateOrderIdempotencyTest` | `OrderApiTest`, `IdempotencyApiTest` (including `concurrentSubmissionsWithTheSameKeyButDifferentBodiesYieldOneOrderAndOne422`; live: 8 parallel requests, one order) | done |
| `OrderValidationClientTest` | `saga/InventoryClientResilienceTest` (11: timeouts, retry, breaker mapping to `UPSTREAM_UNAVAILABLE`) | done |
| `InventoryReservationConcurrencyTest` | `ReservationConcurrencyTest` (H2); PostgreSQL variant `InventoryPostgresIT` skipped | done on H2, **open on PostgreSQL** |
| `InternalEndpointSecurityTest` (inventory) | internal-endpoint checks inside `InventoryApiTest`; edge block in gateway `RoutingAndHeadersTest` | done (no dedicated class) |
| `OrderSagaIT` | `kafka/OrderSagaKafkaIT` (12), `kafka/OrderResilienceKafkaIT` (8), `OrderSagaOrchestratorTest`, `SagaOrchestrationTest` | done |
| `SagaRecoveryJobTest`, `ReconciliationServiceTest` | `saga/SagaRecoveryTest` (12), `saga/ReconciliationTest` (5) | done |
| `FulfillmentSimulationTest`, `NotificationDedupTest` | `FulfillmentServiceTest`/`FulfillmentKafkaTest` (FAILTOWN), `NotificationKafkaTest`/`NotificationServiceTest` (duplicate event, retry with backoff) | done |
| `GatewayAuthTest`, `RateLimitTest`, `InternalRouteBlockedTest`, `DevTokenProfileTest` | `EdgeSecurityTest`, `RateLimitTest`, `RoutingAndHeadersTest`, `NonDevProfileTest` | done |
| `PostgresMigrationIT` per service | the per-service `*PostgresIT` classes (Flyway runs on PostgreSQL inside them) | **written, skipped, never executed** |

### 3.4 Frontend unit tests (exist; Karma/Jasmine, `frontend/oms-ui/src/app/**/*.spec.ts`)

18 spec files; **149 of 149 pass** (`npm test`, Chrome Headless 155; `npx ng build` OK, 2026-10-18): `core/api/apis.spec.ts`, `core/auth/auth.store.spec.ts`, `core/auth/guards.spec.ts`, `core/config.service.spec.ts`, `core/http/api-error.spec.ts`, `core/http/interceptors.spec.ts`, `core/state/cart.store.spec.ts`, `core/state/list-store.spec.ts`, `core/state/polling.spec.ts`, `features/inventory/stock-adjust-dialog.spec.ts`, `features/login/login.spec.ts`, `features/misc.spec.ts`, `features/orders/order-create.spec.ts`, `features/orders/order-detail.store.spec.ts`, `features/orders/order-form.spec.ts`, `features/screens.spec.ts`, `shared/components/components.spec.ts`, `shared/components/timeline.spec.ts`.

### 3.4a React unit and component tests (exist; Vitest + Testing Library + MSW, `frontend/oms-ui-react/src/**/*.test.ts(x)`)

9 test files; **207 of 207 pass** (`npm test`, jsdom, 2026-10-19); coverage (v8, `npm run test:coverage`) **96.3 % statements, 93.5 % branches, 94.5 % functions, 96.7 % lines**; ESLint (typescript-eslint, react-hooks, jsx-a11y) 0 errors; `npm run build` OK; `npm audit` 0 vulnerabilities. Files: `store/slices.test.ts` (slices, selectors, pure helpers), `store/api.test.ts` (RTK Query layer against MSW: headers, GET-only retry, 401 and 429 side effects, serializable errors, tag invalidation, logout cache reset), `core/core.test.ts` (error normalization, config, formatting), `hooks/hooks.test.tsx` (polling backoff, list query, debounce, session expiry), `components/components.test.tsx` (guards, dialogs, tables, timeline), `features/orders/orderForm.test.ts` (Zod schema, idempotency key reuse and renewal), `features/orders/orders.test.tsx` (create, detail with null prices, cancel, polling), `features/screens.test.tsx` (every screen, pagination/filter/sort), `app.test.tsx` (whole app at a URL with a seeded store, role-aware navigation). The tests render the real app against a mock service worker (`src/test/server.ts`) instead of mocking modules. Three harmless "not wrapped in act(...)" notices remain. Not covered: a real browser (jsdom only; Playwright covers that), accessibility audits (no axe), visual regression.

### 3.5 End-to-end (mock and live), dual-UI matrix

The same specs run against both UIs; `E2E_UI` (`angular` default, or `react`) selects the UI served by the mock backend (Angular `frontend/oms-ui/dist/oms-ui/browser`, React `frontend/oms-ui-react/dist`) and the matching live URL.

| UI | Mock backend (port 4300) | Live stack (UI -> gateway -> real services) |
|---|---|---|
| Angular (`E2E_UI=angular`, live URL :4200) | 18 / 18 | 18 / 18 |
| React (`E2E_UI=react`, live URL :4201) | 18 / 18 | 18 / 18 |

Matrix executed 2026-10-19 (the live stack used the gateway on port 18085 via `OMS_GATEWAY_PORT` because a foreign process held 18080). Passing both columns for both rows is the parity evidence: one contract, two clients. A spec that passes for only one UI is a UI defect or a contract ambiguity, not a reason to fork the spec.

`tests/e2e/specs/order-lifecycle.spec.ts` (18 tests, serial, 1 worker) with `helpers.ts`:
authentication and navigation (wrong password generic error, redirect to login and back, customer nav + UX guard, operator nav); order lifecycle (catalog to cart, create to FULFILLED, cancel, insufficient stock SKU-1020, duplicate submit idempotent, API contract replay/422/400, FAILTOWN compensates to FAILED, operator investigates and retries); operations (stock adjust, dashboard health and messaging, audit history, fulfillment and notifications views, reconciliation, customer sees only own orders and 404 for another's).
* **Mock mode** (default, `tests/e2e/mock-backend/server.js`): 18/18; proves the UI against the contract only.
* **Live mode** (`E2E_BASE_URL=http://localhost:4200` + `E2E_DEMO_PASSWORD`, UI -> gateway -> real services on Kafka + H2): **18/18** on 2026-10-18. The first live run found 2 real defects (D-03, D-04 in `docs/validation/integration-defects.md`). One expectation differs from the mock: retrying a TERMINAL failed order is 409.
* Scripted live checks outside Playwright: `scripts/smoke-test.mjs` 10/10, `scripts/live-flows.mjs` 54/54, `tests/e2e/oversell-live.mjs`, `tests/e2e/resilience-live.mjs`, `scripts/run-api-collection.mjs` 41/41 (own Node interpreter; the collection was never opened in Postman/newman).

### 3.6 Non-functional

| Type | Asset | State |
|---|---|---|
| Load | `performance/order-flow.js`, `performance/oversell.js`, `performance/README.md` | scripts exist; **k6 never run** (no binary). No latency/throughput numbers exist; see `docs/known-limitations.md` |
| Security | threat-model test matrix in `docs/security/threat-model.md` section 5 | covered by `SecurityApiTest`, `OrderSecurityTest`, gateway `EdgeSecurityTest`/`RoutingAndHeadersTest`, `LogMaskingTest` and the live cross-customer checks; consumer-side producer-ownership validation and a per-service log-leak scan remain open (threat model section 5) |
| Static analysis | CodeQL, Semgrep, Trivy, gitleaks, `npm audit` in ci.yml | defined in `ci.yml`, never executed (no GitHub Actions run) |
| Infra | `scripts/validate-infra.sh`, `scripts/check-observability.py` | static checks only (`validate-infra.sh` 47 PASS / 0 FAIL / 2 SKIP; observability check OK; metric names verified against a live scrape) |
| Mutation / chaos | not planned for v1 | gap, see risk R-02 (recovery paths are covered by `SagaRecoveryTest`, `OrderResilienceKafkaIT` and `tests/e2e/resilience-live.mjs`) |

## 4. Coverage and quality gates

* JaCoCo 0.8.13 is configured in `services/pom.xml`; no minimum threshold is enforced yet (re-verified 2026-10-18: no `minimum` rule in `services/pom.xml`). Proposed gate: 80 percent line, 90 percent on `OrderStateMachine`, saga and reservation classes.
* Every bug fix adds a failing test first.
* Contract drift check: `specs/openapi/*.json` (4 service exports plus the generated gateway file) and `specs/events/*.schema.json` (15 events plus envelope) exist. Locally, `node scripts/export-openapi.mjs --check` (no drift on 2026-10-18) and `scripts/validate-events.mjs` (218 live records valid) do the comparison; neither is wired into `ci.yml` yet (open).

## 5. Test data

Synthetic only: customers `c0000000-0000-0000-0000-00000000000{1,2,3}`, SKUs `SKU-1001..SKU-1020` (SKU-1020 stock 0, SKU-1019 stock 3), city `FAILTOWN` for fulfillment failure, `fail.example.test` for notification failure. Seeds exist only under profile `dev` (`V1000__seed_customers.sql` in order-service, `V1000__seed_products.sql` in inventory-service; verified by `SeedMigrationTest` and `SeedDataTest`). Tokens are minted by `JwtTestTokens`; passwords come from env (`OMS_DEMO_PASSWORD`/`E2E_DEMO_PASSWORD`), never constants committed for real environments.

## 6. Exit criteria per release

All unit and embedded-Kafka tests green; PostgreSQL ITs green in CI (not skipped; never achieved so far, Docker was unavailable); Playwright live-mode green (achieved 18/18 locally); threat-model tests green or risk accepted; no HIGH/CRITICAL scanner findings with a fix; test report states skipped counts.

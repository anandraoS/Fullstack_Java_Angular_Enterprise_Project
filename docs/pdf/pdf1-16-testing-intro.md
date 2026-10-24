## Strategy

The test pyramid is deliberately bottom-heavy. Pure domain logic (the order state machine, fulfillment state machine, shortage calculation, idempotency fingerprint, backoff policy) is tested exhaustively and in milliseconds. Spring slices and full-context tests with H2 in PostgreSQL mode and embedded Kafka verify wiring, persistence and messaging. A small number of browser tests and load scripts sit at the top.

{{diagram:14-testing-pyramid|Testing pyramid for the platform}}

## Techniques used

| Technique | Where | What it proves |
|---|---|---|
| Table-driven state machine tests | `services/order-service/src/test` (state machine tests), TDD walk-through in `docs/tdd/order-state-machine-tdd.md` | Every valid and invalid status and trigger pair behaves as documented, duplicates are no-ops |
| Concurrency test with `ExecutorService` and `CountDownLatch` | `services/inventory-service/src/test/java/com/enterprise/oms/inventory/ReservationConcurrencyTest.java` | No oversell across the three reservation strategies |
| Mockito unit tests | `services/inventory-service/src/test/java/com/enterprise/oms/inventory/service/ReservationServiceUnitTest.java` | Orchestration logic in isolation (mocks, argument captors, interaction verification) |
| MockMvc and WebTestClient API tests | `services/*/src/test` (`*ApiTest`) | Status codes, problem+json bodies, authorization matrix |
| Embedded Kafka integration tests | `services/order-service/src/test` (`*KafkaIT`) | Saga across topics, retry, DLT, resilience |
| Testcontainers PostgreSQL and Kafka | `*PostgresIT` classes, annotated `disabledWithoutDocker` | PostgreSQL-specific behaviour (skipped when Docker is absent) |
| BDD scenarios | `tests/bdd/*.feature`, executed through `InventoryBddScenariosTest` and the fulfillment equivalent | Business-readable acceptance criteria |
| Angular unit tests | `frontend/oms-ui/src/app/**/*.spec.ts` (Karma and Jasmine) | Stores, pure reducers, guards, interceptors |
| React unit and component tests | `frontend/oms-ui-react/src/**/*.test.ts(x)` (Vitest, Testing Library, MSW) | Slices, selectors, RTK Query layer, guards, forms, idempotency, polling, every screen; 207 of 207 pass, coverage 96.3 % statements |
| Browser end-to-end | `tests/e2e` (Playwright, mock backend and live mode) | 18/18 against the mock backend and 18/18 live (UI to gateway to real services); the live run found a real blank-page defect |
| Load and oversell | `tests/e2e/oversell-live.mjs` (run), `performance/order-flow.js`, `performance/oversell.js` (k6, never run) | Live burst of 30 parallel orders for stock 3: 3 FULFILLED, 27 REJECTED; k6 scripts only syntax-checked |
| Smoke test | `scripts/smoke-test.mjs` | Local stack answers end to end |

TDD is shown as a worked practice: `docs/tdd/` records the red, green, refactor steps for the state machine and the inventory reservation, and the flawed-AI regression tests were written failing first.

## Quality gates

JaCoCo is configured in the reactor for coverage reports. CodeQL, Semgrep, Trivy, `npm audit` and gitleaks are defined in CI but, as stated in the limitations section, CI has not run on a runner.

## Evidence for this build

The tables below are generated when the PDF is built from the actual `surefire-reports` and `failsafe-reports` XML files under each module's `target/` directory, so a rebuild after another test run reports the new numbers. Skipped counts are shown as they are: skipped is not passed.

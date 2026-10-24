# Repo context (always load this first)

Enterprise Order Management and Fulfillment Platform (OMS). Synthetic data only. Normative design:
`docs/architecture/00-shared-contract.md` (the "contract"). If code and contract disagree, fix one in the same change
and say which.

## Shape
* Backend: Java 17, Spring Boot 3.5.x, Maven reactor `services/pom.xml`; gateway physically in `gateway/`.
  Services: order-service (18081), inventory-service (18082), fulfillment-service (18083), notification-service (18084),
  api-gateway (18080, Spring Cloud Gateway, reactive). Shared library: `services/platform-common`
  (package `com.enterprise.oms.common.*`: audit, error, events, idempotency, messaging, observability, outbox,
  security, web). Test helpers: `services/platform-test-support`. Local embedded Kafka: `services/local-stack` (19092).
* Frontend (two UIs, one gateway contract, ADR-0013): Angular 20 + Material, `frontend/oms-ui` (dev server 4200), and
  React 19 + Redux Toolkit/RTK Query + MUI, `frontend/oms-ui-react` (dev server 4201); both proxy `/api` to 18080.
  The original brief mandated Angular; React was added later at the user's explicit request. Keep behaviour in step.
* Data: PostgreSQL 16 (Compose/K8s), H2 in PostgreSQL mode locally using the SAME Flyway SQL. One database per service.
* Messaging: Kafka, JSON envelope `EventEnvelope<T>`, key = `orderId`, topics `oms.<domain>.<events|commands>.v1` + `.DLT`.
* Redis only for rate limiting / optional cache; must degrade to in-memory.

## Non-negotiable invariants
1. Authorization lives in the backend. UI guards are UX. Customers see 404 (not 403) for other customers' orders.
2. State change + history + audit + outbox event commit in ONE DB transaction. Never publish to Kafka inside the tx.
3. Every consumer is idempotent (`processed_events` unique row in the business tx) and tolerant to unknown fields.
4. Reservation uses one atomic conditional UPDATE, not read-then-write. Lock rows in sorted productId order.
5. `POST /orders` requires `Idempotency-Key`; same key + same body replays, different body -> 422.
6. Errors are RFC 7807 with `code`; no stack traces, SQL or secrets in responses or logs.
7. No secrets in code, config, tests or docs. JWT secret only via `OMS_JWT_SECRET`; tests generate theirs.
8. Additive-only changes inside `/api/v1` and inside an event `schemaVersion`.

## Commands (never export JAVA_HOME; pass it inline)
```
JAVA_HOME=/c/Users/sahukari.rao/.jdks/graalvm-jdk-17.0.12 \
  ~/.m2/wrapper/dists/apache-maven-3.9.12-bin/5nmfsn99br87k5d4ajlekdq10k/apache-maven-3.9.12/bin/mvn \
  -B -f services/pom.xml -pl order-service -am test
```
* Frontend: `npm ci`, `npx ng test --watch=false --browsers=ChromeHeadless`, `npx ng build` inside `frontend/oms-ui`;
  `npm ci`, `npm test`, `npm run lint`, `npm run build` inside `frontend/oms-ui-react`; e2e: `E2E_UI=react npx playwright test` in `tests/e2e`.
* Local run without Docker: `scripts/run-local.sh` (prints a generated demo password), stop with `scripts/stop-local.sh`.
* Docker-dependent tests (Testcontainers) are `disabledWithoutDocker`; they are SKIPPED on the dev machine. Say so, do
  not report them as passed.

## Where things live
| Need | Path |
|---|---|
| Order state machine (pure domain) | `services/order-service/src/main/java/com/enterprise/oms/order/domain/` |
| Reservation domain | `services/inventory-service/src/main/java/com/enterprise/oms/inventory/{domain,reservation,repo,service}` |
| Outbox / idempotent processor | `services/platform-common/.../outbox`, `.../idempotency` |
| Error codes and handler | `services/platform-common/.../error` |
| Security / current user | `services/platform-common/.../security` |
| Flyway: common V1..V8, service V10+, seed V1000+ | `services/platform-common/src/main/resources/db/common`, `<svc>/src/main/resources/db/migration` |
| ADRs, runbooks, threat model | `docs/adr`, `docs/runbooks`, `docs/security/threat-model.md` |

## Working agreements for assistants
* Smallest diff that satisfies the spec. No drive-by refactors, no new dependencies without an ADR note.
* Write the failing test first when fixing a bug; name it after the behaviour.
* Do not invent class names, endpoints or metric names: grep first. Contract names win.
* State honestly what was run and what was not.
* Module rules: `context/01-architecture-rules.md` ... `context/10-saga-rules.md`.

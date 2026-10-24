# Validation report (integration and live verification)

Date of all runs: **2026-10-18** (local time +05:30), except section 7 (React front end, **2026-10-19**). Machine: Windows 11 Enterprise, Git Bash, Node v24.13.1, GraalVM JDK 17.0.12 (by full path, `JAVA_HOME` set inline per command only), Maven 3.9.12, Chrome 155 (Playwright `channel: chrome`, headless), Playwright 1.64.1, Angular 20. **No Docker.** Everything below ran against the local no-Docker stack: embedded KRaft Kafka (`local-stack`, port 19092), order/inventory/fulfillment/notification services (H2 in PostgreSQL mode, same Flyway SQL), gateway on 18080, built Angular UI served by `scripts/serve-ui.mjs` on 4200.
All data is synthetic. Secrets were random per run (`OMS_JWT_SECRET`, `OMS_DEMO_PASSWORD`), never written to a deliverable (searched the repo for the run's actual values: no hit).

## 1. Result at a glance

| Area | Result |
|---|---|
| Backend reactor `mvn verify` (final) | BUILD SUCCESS, **968 test cases, 939 executed, 939 passed, 0 failed, 29 skipped (Docker/Postgres/benchmark)**; table in `backend-test-summary.md` |
| Frontend unit tests (Angular) | 149 / 149 pass; `ng build` OK |
| React frontend (2026-10-19, section 7) | Vitest 207 / 207, coverage 96.3 % statements; ESLint 0 errors; build OK; `npm audit` 0; Playwright 18/18 on mock and live for each UI |
| Smoke (`scripts/smoke-test.mjs`) | 10 / 10 checks, repeated on 3 stacks |
| API flows (`scripts/live-flows.mjs`) | 54 / 54 checks (first run 53/54: the one failure was my wrong expectation, see 3.6) |
| Oversell (`tests/e2e/oversell-live.mjs`) | 30 parallel orders for SKU-1019 (stock 3): 3 FULFILLED, 27 REJECTED, never above 3 committed (201 samples); passed twice on the fixed build |
| Resilience (`tests/e2e/resilience-live.mjs`) | passed (order parked while fulfillment down, completes after restart, exactly one fulfillment row) |
| Playwright **live** (UI -> gateway -> real services) | **18 / 18** (final stack); first live run found 2 real defects, see 3.9 |
| Playwright **mock** (mock backend) | **18 / 18** (re-run at the end) |
| Postman collection executed by Node runner | 41 / 41 |
| Event schemas vs live Kafka | 218 records (final capture; 364 in an earlier one), all valid, 15 / 15 event types observed, 0 in DLTs |
| Metric names in `observability/` vs live scrape | 33 names referenced: 32 found, 1 external (`up`), **0 missing** (first run: 5 missing, fixed) |
| Defects found and fixed | 11 (`integration-defects.md`), plus a list of known issues not fixed |
| Static infra validation (`scripts/validate-infra.sh`) | 47 PASS, 0 FAIL, 2 SKIP (docker compose config, gitleaks) |

## 2. Commands to reproduce (from the repo root)

```bash
# 1. build + test everything (about 19 min; log under .run/, which is git-ignored)
JAVA_HOME=/c/Users/sahukari.rao/.jdks/graalvm-jdk-17.0.12 ~/.m2/wrapper/dists/apache-maven-3.9.12-bin/5nmfsn99br87k5d4ajlekdq10k/apache-maven-3.9.12/bin/mvn -B -f services/pom.xml verify
node scripts/test-summary.mjs --md docs/validation/backend-test-summary.md
(cd frontend/oms-ui && npm test && npx ng build)
# 2. start the stack (random secrets; password printed once; takes ~2.5 min on this machine)
bash scripts/run-local.sh --no-build --json-logs --ui          # --ui serves the built UI on :4200
set -a; . .run/secrets.env; set +a                              # exports OMS_DEMO_PASSWORD (+ JWT secret) into THIS shell only
# 3. live checks
node scripts/smoke-test.mjs
node scripts/live-flows.mjs
node tests/e2e/oversell-live.mjs 30
node tests/e2e/resilience-live.mjs
(cd tests/e2e && E2E_BASE_URL=http://localhost:4200 E2E_DEMO_PASSWORD="$OMS_DEMO_PASSWORD" npx playwright test)
(cd tests/e2e && npx playwright test)                           # mock mode (no E2E_BASE_URL), starts mock-backend on :4300
node scripts/run-api-collection.mjs
bash scripts/capture-events.sh && node scripts/validate-events.mjs
node scripts/capture-api-examples.mjs                           # rewrites docs/api/examples.md (OMS_JWT_SECRET also exported -> expired-token example)
node scripts/export-openapi.mjs --check && node scripts/gen-gateway-openapi.mjs
node scripts/check-metric-names.mjs
node scripts/correlation-trace.mjs <correlationId> <orderId>   # needs --json-logs
# 4. generators and static checks
node scripts/gen-event-schemas.mjs && node scripts/gen-api-collection.mjs
bash scripts/validate-infra.sh && python scripts/check-observability.py
# 5. stop everything (releases the jars)
bash scripts/stop-local.sh
```

## 3. Checks that were run

### 3.1 Build and unit/integration tests
| Check | Command | Actual result |
|---|---|---|
| Baseline reactor | `mvn -B -f services/pom.xml verify` (19:06-19:45, 38 min 41 s while I was editing in parallel) | BUILD SUCCESS; 896 unit + 61 IT cases, 0 failures, 29 skipped |
| Final reactor | same command (21:13-21:32, 18 min 39 s, idle machine) | BUILD SUCCESS; 907 unit + 61 IT = 968 cases, 939 executed, 0 failures, 29 skipped (`backend-test-summary.md`) |
| New regression tests, targeted | `mvn -o -pl ... test -Dtest=...` before the final run | `ProdProfileDocsDisabledTest` (4 services), `MessagingMetricsTest`, `PlatformDefaultsTest`, `BulkheadConcurrencyTest` (also shown to FAIL with the old 25-call limit), `OrderTransitionsLoggingTest`, `FulfillmentServiceTest` all pass |
| Frontend | `npm test` (Chrome Headless 155) / `npx ng build` | 149 of 149 SUCCESS; build OK |

### 3.2 Stack start
`scripts/run-local.sh` failed the first time (D-01, a Node crash in the readiness probe). After the fix it started cleanly three times from the shell and a fourth time at the end, when port 18080 turned out to be occupied by **a different project's process** (`cisco-fde-ai-ops-platform/.../ops-platform-1.0.0.jar`, PID 39348, started by someone else after my previous stop): my gateway could not bind and `run-local.sh` still reported "ready" because the foreign app answered the health URL on that port. I did NOT stop that process. For the last check (the Postman collection run) I started my gateway on port 18090 with the same jar and environment and ran the collection against it; everything else in this report ran on port 18080 before the conflict appeared. Fix made afterwards: `run-local.sh` now refuses to start when any of the ports 19092/18080-18084 is already taken (tested with a dummy listener on 18080: exit 1, nothing started; the foreign process had disappeared again a few minutes after I saw it).

### 3.3 Smoke and API flows (through the gateway)
`node scripts/smoke-test.mjs` PASS 10/10 (401 without token, login, products, create 202, idempotent replay + header, missing key 400, FULFILLED, history).
`node scripts/live-flows.mjs` PASS 54/54: happy path to FULFILLED (history path `CREATED>VALIDATED>INVENTORY_RESERVED>CONFIRMED>FULFILLED`, saga COMPLETED with 8 steps, reservation COMMITTED+shipped, stock 498 -> 496, tracking number, 5 notifications SENT, 5 audit entries, customer 403 on saga/fulfillment/audit); insufficient stock (SKU-1020 stock 0, SKU-1019 over-ask 4 > 3 with stock untouched, all-or-nothing mixed order, listed in `/admin/orders/failed`); idempotency (replay with `Idempotent-Replay: true`, 422 reuse, 400 missing, 8 parallel requests with one key -> one order); cancel (immediately: `CREATED>VALIDATED>CANCELLED`; at CONFIRMED: `...CONFIRMED>COMPENSATION_PENDING>CANCELLED` with reservation RELEASED; terminal: 409 `INVALID_STATE_TRANSITION`; operator cancel); FAILTOWN (`CONFIRMED>COMPENSATION_PENDING>FAILED`, reservation RELEASED/FULFILLMENT_FAILURE, available restored, fulfillment FAILED attempts=3, retry of terminal order 409); cross-customer (404 on get/history/cancel/profile, forced own-orders filter, 403 creating for another customer, customers see `available` only, `/internal/**` 404 at the edge); notification retry path (customer3 `fail.example.test`: every notification SENT only after attempt 3, transient RETRYING with `lastError` observed, cleared on success); ops (4x messaging: processed > 0, dead-lettered 0, outboxPending 0, lagAvailable true; aggregate health UP; reconciliation admin 200 / operator 403).

### 3.4 Concurrency
Run 1 (before fix): `{"202":29,"503":1}`: the 503 was the gateway's hidden 25-call bulkhead (D-02); inventory invariants still held (3 FULFILLED, 26 REJECTED, max committed 3). Runs 2 and 3 (fixed gateway, fresh stock): burst of 30 accepted in ~4 s, **FULFILLED 3, REJECTED 27**, `max(shipped+reserved) = 3`, `min(available) = 0`, 30 reservation rows of which exactly 3 RESERVED/COMMITTED, ledger `onHand 3 -> 0, reserved 0 -> 0`. Orders were spread over four identities (customer1, customer2, operator1, admin1) because `POST /orders` is rate limited per subject (capacity 20): this is a measured limit of the design, not a test artefact. Numbers are from `.run/oversell*.out` of the actual runs; the script recomputes every number (no constants).

### 3.5 Resilience
`node tests/e2e/resilience-live.mjs`: scenario A (stop fulfillment-service, place order, 8 s later still `CONFIRMED`, restart): order FULFILLED 39 s after the start command (includes JVM boot), exactly one fulfillment row. Scenario B (stop triggered when the order is first seen CONFIRMED; it was still CONFIRMED when the stop completed): FULFILLED after restart, no duplicate row. This shows that the `StartFulfillment` command waits durably in Kafka and the consumer resumes at its committed offset.

### 3.6 Playwright
Live: first run 1 failed (blank order page, D-03) and the next surfaced two spec-wording differences (D-04); then 18/18 on two stacks, the last one after the final rebuild. One spec expectation changed for the real backend: retrying a TERMINAL failed order is 409 (accepted alongside the mock's success). Mock: 18/18 (2.9 min). `live-flows.mjs` first run failed one check (`lastError` after success): wrong expectation, the service clears `lastError` on success; the script now observes the RETRYING state instead.

### 3.7 Contract artefacts
* **OpenAPI:** `specs/openapi/{order,inventory,fulfillment,notification}-service.json` exported from `/v3/api-docs` of the running services (12/10/5/4 paths); `--check` reports no drift on the final stack. `specs/openapi/api-gateway.json` is **generated, not exported** (the reactive gateway has no springdoc) and describes only the gateway's own endpoints (token, ops/health) plus the proxied route table.
* **Events:** `specs/events/*.schema.json` (15 event schemas + envelope, written to match contract section 7); `scripts/capture-events.sh` consumed every `oms.*` topic from the live broker with a throw-away consumer group (Java single-file program, `tools/capture`), `scripts/validate-events.mjs` validated 218 records: 218 valid, 0 invalid, all 15 types observed, 0 DLT records. The committed sample is `tests/e2e/samples/kafka-events.sample.jsonl` (30 records). The validator is a small purpose-built subset implementation (`scripts/lib/mini-jsonschema.mjs`, no `$ref`, no `additionalProperties` restriction on purpose: tolerant reader); a negative test (missing `items`, non-UUID `orderId`, wrong `producer`) is reported invalid. No general-purpose JSON Schema engine was used.
* **API examples:** `docs/api/examples.md` regenerated by `scripts/capture-api-examples.mjs` from live responses: 38 examples (success, 400 validation, 400 missing key, 422 reuse, replay, 404, 403, 409 invalid cancel, 409 stock conflict, 401 none/forged/expired/`alg:none`, 413, 429, saga, audit, messaging, health). Tokens redacted.
* **Collection:** `tests/api-collection/oms.postman_collection.json` (41 requests), `oms.postman_environment.template.json` (placeholders only), `oms.http` (REST Client), generated from one catalog by `scripts/gen-api-collection.mjs`. The collection was executed by `scripts/run-api-collection.mjs`, my own small interpreter: 41/41 pass. **It was not opened in Postman or newman, and `oms.http` was not run in the VS Code REST Client**, so the Postman test scripts and the `.http` variable syntax are unverified in their native tools.

### 3.8 Observability
* `node scripts/check-metric-names.mjs` scraped all five `/actuator/prometheus` endpoints (313 series names) and checked the 59 expressions of 5 dashboards and 12 alert rules. First run: MISSING `kafka_consumer_fetch_manager_records_lag_max`, `oms_events_dead_lettered_total`, `oms_events_duplicates_total`, `oms_events_retries_total`, `spring_kafka_listener_seconds_bucket`, and `http_server_requests_seconds_bucket` existed on 2 of 5 apps only. After the fixes (D-05..D-07): 0 missing. The EXTERNAL list is just Prometheus' own `up`; the dashboards use no kafka-exporter/postgres-exporter series, so no infra metric needed to be excluded.
* Correlation trace: `docs/validation/correlation-trace-sample.md` (27 real JSON log lines, 5 services, e-mail masked). traceId is NOT stable across Kafka hops here and no OTel collector/Tempo/Loki/Grafana/Prometheus server ran, so dashboards were validated by metric name against the live scrape, not rendered, and alert rules were not evaluated by Prometheus.
* `python scripts/check-observability.py`: OK (5 dashboards / 40 panels, 12 alert rules).

### 3.9 Security
* Kubernetes base ConfigMap: profile default `json-logs`; `kustomize build` renders `dev,json-logs` (dev, test), `prod,json-logs` (prod), base `json-logs`; `scripts/validate-infra.sh` 47 PASS / 0 FAIL / 2 SKIP, including kubeconform on the rendered overlays.
* Prod docs off: `ProdProfileDocsDisabledTest` x4 (config-level). Live gateway probe (dev profile): `/v3/api-docs` 401 without token, 404 with token; `/swagger-ui.html` 401; `/swagger-ui/index.html` 404; `/api/v1/internal/products/validate` 404; path traversal into `/actuator` via `/ops/order/..` 404; `/actuator/env` 401; gateway's own `/actuator/health` 200 and `/actuator/prometheus` open (documented).
* `docs/security/threat-model.md` section 10: HS256 caveat, RS256/JWKS path (code reviewed, **untested**, gaps listed), prometheus exposure.

## 4. NOT EXECUTED (stated plainly)

| Not done | Why / consequence |
|---|---|
| Docker, Docker Compose, image builds (all Dockerfiles), nginx image of the UI | Docker is not installed. `docker compose config` was SKIPPED by `validate-infra.sh`; compose files were only structure-checked. |
| `kubectl apply` / any cluster, NetworkPolicy enforcement, HPA/KEDA, Ingress, ServiceMonitor, PrometheusRule on a cluster | no cluster. Manifests were rendered with kustomize and checked with kubeconform (static) only. |
| `terraform plan/apply` | only `fmt -check` and `validate` ran (no credentials). |
| k6 (`performance/*.js`) | k6 binary not available; only `node --check` syntax of the scripts. No load/latency numbers exist beyond the 30-order burst above. |
| Real GitHub Actions run, Jenkins | workflow/Jenkinsfile only parsed. |
| Real Kafka cluster (multi-broker, replication 3, TLS/SASL, ACLs) | only the embedded single-node KRaft broker; delivery semantics were observed on it (no duplicates, no DLT records), not proven for a cluster. |
| Redis (rate limiter, product cache), `oms.redis.enabled=true` | in-memory fallback only; `RedisOrderRateLimiterTest` etc. are in the suite but not against a real Redis. |
| OIDC / JWKS / RS256 (gateway and services) | no IdP; code path exists, no test, no run. |
| PostgreSQL (any), Testcontainers PostgreSQL/Kafka tests | 28 tests skipped (`disabledWithoutDocker`); all runtime evidence is on H2 in PostgreSQL mode. Row locking (`pessimistic` strategy), Postgres-specific plans and `docs/database/*` EXPLAIN numbers are not re-verified. |
| OpenTelemetry collector, Tempo, Loki/promtail, Grafana, Prometheus server | not started; see 3.8. End-to-end distributed trace not shown. |
| Postman / newman / VS Code REST Client themselves | only my Node interpreter of the collection. |
| `ng serve` (dev proxy) | the built UI served by `scripts/serve-ui.mjs` was used instead. |
| Multiple gateway/service replicas, rolling restarts, graceful-shutdown drain under load | single instance of everything. |
| Browsers other than Chrome 155, accessibility audit, mobile viewports | not run. |
| Long soak, memory leak and GC behaviour | not run; JVMs capped at 384 MB each for the demo. |
| `git commit` / `git push` | nothing committed or pushed (the git root is the home directory with everything untracked); deliverables are files in the working tree. |

## 5. Other honest observations
* Stack start takes about 2.5 minutes on this machine (six JVMs at once); `run-local.sh` waits up to 300 s per service.
* `POST /orders` is rate limited per caller (20 burst, 5/s): fine for users, and it shaped the oversell test design.
* Contract deviations and additions from this pass are in `docs/architecture/00-shared-contract.md` section 15.

## 6. Finalization pass (after the integration run)

Changes made after the runs above, and what was re-verified:

* **Infrastructure alignment:** JVM tmpdir is now `/tmp` everywhere (Dockerfiles, Compose `tmpfs`, Kubernetes `emptyDir` at `/tmp`); the prod overlay, NetworkPolicy and Terraform security group agree on MSK IAM authentication (port 9098, no SCRAM); Terraform gained `aws_secretsmanager_secret.service_db` (containers only) for the per-service DB credentials the ExternalSecret example references. `bash scripts/validate-infra.sh` was re-run: **47 PASS, 0 FAIL, 2 SKIP** (docker compose config, gitleaks), including `terraform fmt -check` and `terraform validate`.
* **Histograms (D-07):** re-checked in code: platform-common defaults cover the four servlet services and the gateway sets its own; nothing further needed.
* **New code:** `RetentionPurgeJob` (platform-common, `oms.retention.*`) with `RetentionPurgeJobTest` (2 cases, H2). `platform-common` suite re-run green (68 cases); notification and fulfillment module Kafka/API/context tests re-run green with the new auto-configuration active. The full reactor WAS re-run afterwards (`mvn -B -f services/pom.xml verify`, 2026-10-18, BUILD SUCCESS): **970 cases (909 unit + 61 IT), 941 executed, 941 passed, 0 failed, 29 skipped**; `backend-test-summary.md` was regenerated from that run. Older 968/939 figures elsewhere in these docs are the earlier run.
* **Documentation:** governance docs refreshed against the code, `docs/known-limitations.md` added, PDFs rebuilt (architecture PDF gained Appendix B with the 11 defects; interview PDF gained Q145 to Q150 on them).

## 7. React front end verification (`frontend/oms-ui-react`, 2026-10-19)

Context: the original brief mandated Angular. The user later explicitly asked for a React + Redux Toolkit front end as well; it was added as a second, feature-equivalent UI on the unchanged backend and gateway contract (ADR-0013). Nothing under `services/` or `gateway/` was changed for it. Same machine as above (Windows 11, Git Bash, Node v24), no Docker.

| Check | Result (executed 2026-10-19) |
|---|---|
| Vitest unit and component tests (`npm test`, jsdom + MSW) | **207 / 207 pass** in 9 files |
| Coverage (`npm run test:coverage`, v8) | **96.3 % statements, 93.5 % branches, 94.5 % functions, 96.7 % lines** |
| ESLint (typescript-eslint, react-hooks, jsx-a11y) | 0 errors |
| Production build (`tsc -b` strict + `vite build`) | OK |
| `npm audit` (including dev dependencies) | 0 vulnerabilities |
| Playwright, the same 18 specs, React UI, mock backend (`E2E_UI=react`) | **18 / 18** |
| Playwright, React UI, live stack (gateway -> services -> embedded Kafka -> H2) | **18 / 18** |
| Playwright, Angular UI, mock backend (re-run for the matrix) | **18 / 18** |
| Playwright, Angular UI, live stack | **18 / 18** |
| `bash scripts/validate-infra.sh` (re-run) | **47 PASS, 0 FAIL, 2 SKIP** (docker compose config, gitleaks) |

Live-run note: the live stack used the gateway on port **18085** (`OMS_GATEWAY_PORT=18085`) because a foreign process already held 18080. The UIs were served on 4200 (Angular) and 4201 (React) with a same-origin `/api` proxy.

**NOT executed for the React UI (stated plainly):**

* The React Docker image build, the compose profile `react`, and the Kubernetes `frontend-react.yaml` apply (no Docker, no cluster). Only `kustomize build` and `kubeconform` (part of the 47 passing infra checks) touched the manifest.
* The CI and Jenkins changes for the React UI (text only; no GitHub Actions or Jenkins run).
* Accessibility audits: no axe, no Lighthouse, no screen-reader pass. Only lint rules and role-based Playwright locators.
* A narrow-screen (mobile) visual check; only desktop Chrome was used.
* Browsers other than Chrome; contrast measurement; load or soak tests of the UI.

Known limitations of the React UI are in `docs/known-limitations.md` (types copied not generated, `style-src 'unsafe-inline'` for MUI/Emotion, token in `sessionStorage`).

See also `docs/known-limitations.md` and the root `README.md`.

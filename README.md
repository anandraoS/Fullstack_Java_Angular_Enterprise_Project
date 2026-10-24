# Fullstack Java and Angular Enterprise Platform

An Order Management and Fulfillment reference platform: Java 17 and Spring Boot 3.5 microservices behind a Spring Cloud Gateway, event-driven with Kafka (transactional outbox, idempotent consumers, orchestrated saga), PostgreSQL with Flyway (H2 in PostgreSQL mode for the local run), two feature-equivalent UIs on the same gateway contract (Angular 20, and React 19 with Redux Toolkit), Docker/Kubernetes/Terraform assets, observability, tests, and two PDF guides (architecture and interview deep dive).

**Why two front ends:** the original brief mandated Angular (`frontend/oms-ui`). The user later explicitly asked for a React frontend as well, so `frontend/oms-ui-react` was added as a second UI without any backend change (ADR-0013). Both run the same 18 Playwright specs.

**All data is synthetic. This is a demo and learning project, not a production system.** See `docs/known-limitations.md` and the validation summary below for exactly what was and was not run.

## Quick start without Docker (verified path)

Prerequisites: JDK 17, Maven 3.9, Node 20+ (verified with Node 24), Git Bash on Windows (or any POSIX shell). `scripts/run-local.sh` defaults to the author's JDK path; set `OMS_JAVA_HOME` (a JDK 17 directory) and optionally `OMS_MVN` for your machine. `JAVA_HOME` is never exported.

```bash
bash scripts/run-local.sh --json-logs --ui both   # builds missing jars, starts Kafka + 4 services + gateway + UI(s); about 2.5 minutes
#   --ui angular (same as bare --ui): Angular on 4200;  --ui react: React on 4201;  --ui both: both
node scripts/smoke-test.mjs                    # needs OMS_DEMO_PASSWORD, see below
# the UIs are then served at http://localhost:4200 (Angular) and http://localhost:4201 (React); log in with a demo user
bash scripts/stop-local.sh                     # stop everything
```

Build the UI you serve first: `(cd frontend/oms-ui && npm ci && npx ng build)` for Angular, `(cd frontend/oms-ui-react && npm ci && npm run build)` for React. Add `--no-build` to fail instead of building when a jar is missing. The script refuses to start if any of the ports below is already in use; `OMS_GATEWAY_PORT=<port>` moves the gateway when another process holds 18080 (the 2026-10-19 live runs used 18085).

## With Docker Compose (NOT executed in this project)

```bash
docker compose -f infrastructure/compose/docker-compose.yml up --build       # needs the jars built first: mvn -f services/pom.xml package
docker compose -f infrastructure/compose/docker-compose.yml -f infrastructure/compose/docker-compose.observability.yml up   # optional observability stack
```

Docker was not installed on the development machine, so the Dockerfiles and Compose files were only structure-checked. Copy `.env.example` to `.env` and fill in random values first.

## Ports

| Component | Port |
|---|---|
| API gateway (public entry point) | 18080 |
| order-service / inventory-service / fulfillment-service / notification-service | 18081 / 18082 / 18083 / 18084 |
| Embedded Kafka (`local-stack`) | 19092 |
| Angular UI (`--ui` / `--ui angular`) | 4200 |
| React UI (`--ui react`; `--ui both` serves both) | 4201 |

## Demo users and the demo password

Users (gateway `DemoCredentials`, profile `dev` only): `customer1`, `customer2` (CUSTOMER, bound to seeded customers), `operator1` (OPERATOR), `admin1` (ADMIN).

There is **no fixed password**. `run-local.sh` generates a random `OMS_DEMO_PASSWORD` (12 random bytes, hex) and a random `OMS_JWT_SECRET` on every run, passes them to the JVMs through the environment, prints the demo password **once** at the end, and keeps both in `.run/secrets.env` (mode 600, git-ignored, deleted by `stop-local.sh`). To use them in your shell: `set -a; . .run/secrets.env; set +a`. Never commit a secret; `.env.example` contains placeholders only.

## Tests (real numbers; backend and Angular run on 2026-10-18, React on 2026-10-19)

```bash
JAVA_HOME=<jdk17> mvn -B -f services/pom.xml verify          # whole backend, about 19 minutes
node scripts/test-summary.mjs --md docs/validation/backend-test-summary.md
(cd frontend/oms-ui && npm test && npx ng build)             # Angular unit tests and build
(cd frontend/oms-ui-react && npm test && npm run lint && npm run build)   # React: Vitest, ESLint, build (npm run test:coverage for coverage)
node scripts/live-flows.mjs; node tests/e2e/oversell-live.mjs 30     # against a running stack
(cd tests/e2e && npx playwright test)                         # mock mode, Angular (default); set E2E_BASE_URL + E2E_DEMO_PASSWORD for live mode
(cd tests/e2e && E2E_UI=react npx playwright test)            # same 18 specs against the React UI (live: E2E_BASE_URL=http://localhost:4201)
bash scripts/validate-infra.sh                                # static infrastructure checks
```

| Check | Result |
|---|---|
| Backend reactor (`mvn verify`) | BUILD SUCCESS: 968 cases, 939 executed, 0 failed, 29 skipped (28 need Docker/PostgreSQL, 1 opt-in benchmark). Re-run after adding two retention tests: 970 cases, 941 executed, 0 failed, 29 skipped |
| Angular frontend | 149 / 149 unit tests; `ng build` OK |
| React frontend (2026-10-19) | Vitest 207 / 207; coverage 96.3 % statements, 93.5 % branches, 94.5 % functions, 96.7 % lines; ESLint 0 errors; build OK; `npm audit` 0 vulnerabilities |
| Smoke / API flows / Postman collection | 10 / 10; 54 / 54; 41 / 41 (own Node runner) |
| Playwright (same 18 specs, per UI) | Angular: 18 / 18 mock and 18 / 18 live. React: 18 / 18 mock and 18 / 18 live (live stack: gateway on 18085 because a foreign process held 18080) |
| Oversell burst | 30 parallel orders for stock 3: 3 FULFILLED, 27 REJECTED, never above 3 committed |
| Event schemas vs live Kafka | 218 records, all valid, 15 / 15 event types, 0 dead letters |
| Static infra validation | 47 PASS, 0 FAIL, 2 SKIP (docker compose config, gitleaks) |

## Repository map

| Path | Content |
|---|---|
| `services/` | Maven reactor: `platform-common` (outbox, idempotency, audit, retention, security, messaging), `order-service`, `inventory-service`, `fulfillment-service`, `notification-service`, `local-stack` (embedded Kafka), `platform-test-support` |
| `gateway/` | Spring Cloud Gateway: JWT at the edge, rate limit, circuit breaker and bulkhead, demo token endpoint |
| `frontend/oms-ui/` | Angular 20 standalone app (signals, guards, interceptors) |
| `frontend/oms-ui-react/` | React 19 + Redux Toolkit (RTK Query) + MUI app, feature-equivalent to the Angular UI; same Playwright specs (`E2E_UI=react`), ADR-0013 |
| `infrastructure/` | Dockerfiles, Compose, Kubernetes (Kustomize base/overlays, KEDA example, monitoring), Terraform (AWS reference), CI (Jenkinsfile) |
| `.github/workflows/` | GitHub Actions pipeline (never run on a runner) |
| `observability/` | Prometheus rules, Grafana dashboards, Loki, Tempo, OTel collector config |
| `tests/` | `e2e` (Playwright + mock backend), `api-collection` (Postman/.http), `bdd` (Gherkin) |
| `performance/` | k6 scripts (syntax-checked only; k6 never run) |
| `specs/` | `openapi/` exports, `events/` JSON Schemas, `features/` feature specs |
| `ai-engineering/` | Spec-driven AI workflow, prompts, agent templates, 12 flawed-AI examples |
| `docs/` | Architecture, ADRs, governance, runbooks, security, validation, interview material; see `docs/README.md` |
| `scripts/` | Run, stop, smoke, live checks, generators, `validate-infra.sh`, `make-zip.py` |
| `tools/pdf/` | Node/pdfkit pipeline that builds the two PDFs |

## Documentation index

* `docs/README.md`: full index.
* PDFs in `docs/pdf/`: `Fullstack_Java_Angular_Platform_Architecture.pdf` (design, trade-offs, validation, defects appendix) and `Fullstack_Java_Angular_Deep_Dive_Interview_Guide.pdf` (18 chapters including the implementation-backed React/Redux chapter, 164 questions).
* `docs/validation/validation-report.md`, `integration-defects.md`, `backend-test-summary.md`: what was run and what broke.
* `docs/known-limitations.md`: the honest limitations list.
* `docs/architecture/00-shared-contract.md`: the normative contract (ports, topics, events, states).

## Honest validation summary

Executed for real: backend build and tests (embedded Kafka, H2), Angular and React tests and builds, the whole stack running locally, live smoke, API flows, oversell, resilience and Playwright runs, event and metric-name checks, and static infrastructure validation (kustomize, kubeconform, `terraform fmt` and `validate`).

Never executed: Docker and Compose (including the React image and the compose profile `react`), Kubernetes apply (including `frontend-react.yaml`), CI/Jenkins runs, axe/Lighthouse/screen-reader audits, a narrow-screen visual check of the React UI, Terraform plan/apply, k6, GitHub Actions, PostgreSQL (all database evidence is H2 in PostgreSQL mode), real Redis, a real Kafka cluster, OIDC mode, and a Prometheus/Grafana/Tempo run. Eleven defects were found only by running the stack; see `docs/validation/integration-defects.md`.

## License

MIT, see `LICENSE`. The project is a synthetic demonstration; the generated PDFs carry the Cotiviti AI and copyright footer.
# Fullstack_Java_Angular_Enterprise_Project

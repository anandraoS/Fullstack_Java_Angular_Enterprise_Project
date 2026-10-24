# Known limitations

An honest list of what this project does not do, or has not proven. Nothing here is hidden elsewhere; the evidence behind each statement is in `docs/validation/validation-report.md` and `docs/validation/integration-defects.md`. All data is synthetic.

## Not executed (no tooling or environment available)

| Area | State |
|---|---|
| Docker, Docker Compose, image builds | Never executed (Docker not installed). Compose files and Dockerfiles were structure-checked only. |
| Kubernetes | Manifests rendered with kustomize and checked with `kubeconform -strict`; never applied to a cluster. NetworkPolicy enforcement, HPA, Ingress, ServiceMonitor, PrometheusRule are unproven at runtime. |
| Terraform | `fmt -check` and `validate` only. No `plan`, no `apply`, no credentials used. |
| k6 | `performance/*.js` passed `node --check` only; no k6 binary, so no latency or throughput numbers exist. The only load evidence is the 30-order oversell burst. |
| GitHub Actions / Jenkins | Workflow and Jenkinsfile parsed only; never run. |
| PostgreSQL | All runtime evidence is on H2 in PostgreSQL mode with the same Flyway SQL. 28 Testcontainers tests are skipped (`disabledWithoutDocker`). Row-locking behaviour and EXPLAIN plans are not re-verified on PostgreSQL. |
| Real Kafka cluster, Redis, OTel collector, Tempo, Loki, Grafana, Prometheus | Not started. Dashboards were validated by metric name against a live scrape, not rendered; alert rules were not evaluated by Prometheus. |

## Design and behaviour limitations

1. **Outbox relay and scheduled jobs do not claim rows.** With several replicas, `OutboxRelay`, `SagaRecoveryJob`, `FulfillmentProgressJob`, `NotificationDeliveryJob`, `ReservationExpiryJob` and the cleanup jobs can do the same work twice. This is safe because consumers de-duplicate on `eventId` (`processed_events`) and commands are idempotent, but it wastes work and can duplicate log lines and metrics. A `SELECT ... FOR UPDATE SKIP LOCKED` claim (or ShedLock) is the fix; not implemented.
2. **Retention purge is new and untested at scale.** `RetentionPurgeJob` (platform-common, `oms.retention.*`) deletes `processed_events` older than 14 days and `audit_events` older than 365 days by default. It is covered by an H2 test only; the retention values are assumptions (the processed-event window must exceed the longest possible redelivery or DLT replay window).
3. **No DLT replay tool.** Dead-lettered records are visible (`GET /api/v1/ops/messaging`, `dltTopics`) but replaying them is a manual procedure.
4. **`traceparent` is not continuous across Kafka hops.** Each hop starts a new trace in the local run; `correlationId` (in the event envelope and the MDC) is the join key. `docs/validation/correlation-trace-sample.md` shows it across all five services.
5. **Angular `OnPush` is not used.** Components use default change detection (signals and the async pipe are used where convenient). Acceptable at this size, a gap for a large UI.
6. **OIDC/JWKS mode is untested.** The wiring exists in the gateway and platform-common, but no IdP was available, and in OIDC mode the order -> inventory call has no SERVICE token (threat model 10.3). The demo runs in HS256 mode with a random secret per run.
7. **`/actuator/health` and `/actuator/prometheus` are unauthenticated** on every service port and on the gateway's public port (documented in threat model 10.4). In production restrict them with NetworkPolicy or a separate management port.
8. **Autoscaling mismatch.** The KEDA Kafka-lag `ScaledObject` is an example under `infrastructure/kubernetes/keda`, not included in any overlay. The HPA range 2-6 exceeds the 3 partitions per topic: replicas beyond 3 get no partitions of a topic and are idle for consumption.
9. **MSK authentication needs client work.** Terraform enables IAM-only authentication (port 9098) and the prod overlay now points at 9098, but the services do not ship `aws-msk-iam-auth` or set `SASL_SSL` properties; the prod Kafka connection is therefore not usable as-is.
10. **Cancel versus fulfillment race.** A cancel that arrives while `StartFulfillment` is in flight is resolved by the order state machine and compensation, not by a cross-service lock; the window exists and was exercised only through the CONFIRMED-stage cancel in `live-flows.mjs`.
11. **Rate limiting is per instance** unless Redis is enabled (`oms.redis.enabled`); Redis mode is unit-tested but never ran against a real Redis.
12. **Single instance of everything** was tested: no rolling restarts, no multi-replica behaviour, no soak or memory test.

## React front end (`frontend/oms-ui-react`, added 2026-10-19)

The original brief mandated Angular; the user later explicitly asked for a React + Redux front end as well (ADR-0013). Its limitations:

1. **Contract types are a copy, not generated.** `src/models.ts` is a verbatim copy of the Angular models, which in turn follow `00-shared-contract.md`. Neither is generated from `specs/openapi/*.json`; drift between the copies (or from the backend) is caught only by tests and the shared e2e specs.
2. **CSP keeps `style-src 'unsafe-inline'`.** MUI/Emotion injects styles at runtime. Scripts stay `script-src 'self'`. A nonce-based Emotion cache would remove the exception; not done (Angular Material needs the same exception).
3. **No accessibility audit.** Only `eslint-plugin-jsx-a11y` and role-based Playwright locators. No axe, no Lighthouse, no screen-reader pass, contrast chosen not measured.
4. **Docker image, compose profile `react`, Kubernetes `frontend-react.yaml` were never built or applied.** `kustomize build` and `kubeconform` pass; nothing ran in a container or cluster. CI and Jenkins jobs for the React UI were added as text and never executed.
5. **Narrow-screen layout not checked visually.** Only desktop Chrome was exercised; the mobile drawer is unit-tested by logic.
6. **Access token readable by script.** Redux memory plus `sessionStorage` (survives reload); production should use authorization code with PKCE and a BFF with HttpOnly cookies (same trade-off as the Angular app).
7. **Two UIs mean double maintenance.** Unit tests are not shared (Vitest versus Karma); only the 18 Playwright specs are, and they prove behaviour parity only for what those specs cover.
8. **Test noise.** Three harmless "not wrapped in act(...)" notices remain in the Vitest output.

## Not fixed from integration

See the end of `docs/validation/integration-defects.md`: one gateway `WARN` from the Prometheus registry (meter tag conflict), per-hop trace ids, unauthenticated actuator endpoints, OIDC SERVICE token, slow start (about 2.5 minutes for six JVMs).

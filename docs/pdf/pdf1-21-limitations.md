## Known limitations (by design or by environment)

| Area | Limitation | Consequence | What production would need |
|---|---|---|---|
| Identity | HS256 shared-secret JWT and a dev-only token endpoint | Any holder of the secret can mint any identity; services share the secret | OIDC provider, RS256 via JWKS, short-lived tokens, per-service client credentials |
| Service-to-service | SERVICE JWT from the same secret, network trust | No cryptographic workload identity | mTLS or a service mesh plus NetworkPolicies, or OAuth client credentials |
| Discovery and config | Static route URIs, no Eureka and no Config Server | Fine on Kubernetes and Compose; not dynamic on VMs | Kubernetes DNS (already), or Eureka and Config where there is no orchestrator |
| Kafka | Embedded single broker locally, replication factor 1; consumer lag partition-bound (3 partitions) | No HA locally; scaling consumers beyond 3 per group gives nothing | RF 3, `min.insync.replicas=2`, more partitions planned up front, rack awareness |
| Delivery | At-least-once plus idempotent consumers, not exactly-once | A duplicate is possible and absorbed, not prevented | Same, or Kafka transactions where read-process-write is in Kafka only |
| Trace propagation | `correlationId` is propagated end to end and was shown across all five services in a real trace sample; the W3C `traceparent` is NOT continuous across Kafka hops (a new trace id per hop in the local run) | Distributed traces break at the outbox hop; use `correlationId` as the join key | Persist trace context in outbox headers and restore it in the relay |
| Database | PostgreSQL-only behaviour is not proven by H2 tests; PostgreSQL integration tests need Docker | Local green is not PostgreSQL green | Run Testcontainers in CI, run `CREATE INDEX CONCURRENTLY` style migrations with PostgreSQL-specific scripts |
| Multi-replica jobs | The outbox relay has no row claiming and the recovery, reconciliation and expiry jobs have no scheduler lock | With several replicas duplicates are published or work is repeated; tolerated by idempotency but wasteful | Row claiming (SELECT ... FOR UPDATE SKIP LOCKED) or a scheduler lock |
| DLT replay | No replay tool; dead letters are inspected manually | Recovery is a manual procedure | A replay command or admin endpoint with audit |
| Data growth | A `RetentionPurgeJob` now purges `processed_events` (14 days) and `audit_events` (365 days) via `oms.retention.*`; tested on H2 only, retention values are assumptions | Purge never ran against PostgreSQL at volume | Tune retention to redelivery and compliance windows, consider partitioning |
| React frontend | Types copied (not generated) from the contract; `style-src 'unsafe-inline'` for MUI/Emotion; token readable by script (`sessionStorage`); no axe, Lighthouse or screen-reader audit; Docker image, compose profile `react`, Kubernetes manifest and CI jobs never executed; only desktop Chrome | Two UIs can drift; a CSP exception; XSS exposure of the token; unknown WCAG status; deployment assets unproven | Generate types from the OpenAPI export, nonce-based Emotion cache, PKCE plus BFF with HttpOnly cookies, run axe and Lighthouse, build and scan the image |
| Frontend | zone.js change detection, components not using `OnPush`, GET retries without jitter | Higher change-detection cost, retry synchronisation under load | `OnPush` or zoneless, jittered backoff |
| OpenAPI | All four services are exported from live `/v3/api-docs`; the gateway spec is generated, not exported | Contract drift risk remains if the export is not diffed in CI | Run `scripts/export-openapi.mjs --check` in CI |
| Infrastructure | Compose, Kubernetes and Terraform were validated statically; Docker was unavailable | Untested in a live cluster | Rehearse on a real cluster, apply Terraform in a sandbox account |
| MSK auth | Terraform and the prod overlay now agree on IAM (port 9098), but the services ship no `aws-msk-iam-auth` library and set no SASL properties | Prod Kafka connection is not usable until the client side is added | Add the library and `SASL_SSL` properties, then test against MSK |
| Deployment safety | `progressDeadlineSeconds` is not set although the runbook recommends it | A stuck rollout takes longer to be flagged | Set it and alert on it |
| Load and capacity | k6 scripts exist; no recorded production-scale run | Capacity numbers are design targets | Run against a production-like environment, record results |
| CI | `.github/workflows/ci.yml` has never run on a GitHub runner; `deploy-test` is a placeholder | Pipeline correctness is unproven | Run it, pin Actions to commit SHAs, add environment protection |
| Business scope | No payment, tax, pricing rules, carriers or real notifications | Not a complete commerce system | Integrate providers behind the existing ports |

## Documentation freshness

The governance documents (HLD, LLD, test strategy, risk register, runbook index) were refreshed against the code after integration. The authoritative sources, in order, are the code, the shared contract (`docs/architecture/00-shared-contract.md`), the validation report and then the narrative documents. When they disagree, trust the code. The full list of limitations is in `docs/known-limitations.md`; its additional items are:

* Outbox relay and scheduled jobs do not claim rows (duplicate work with replicas, safe because of idempotency).
* No DLT replay tool.
* Angular `OnPush` is not used.
* React UI: types are copied not generated, `style-src 'unsafe-inline'` remains for MUI/Emotion, no accessibility audit, and the React image, compose profile, Kubernetes manifest and CI jobs were never executed.
* OIDC mode is untested and lacks the SERVICE token for order to inventory calls.
* `/actuator/health` and `/actuator/prometheus` are unauthenticated.
* The KEDA example is not in any overlay, and the HPA range 2 to 6 exceeds the 3 partitions per topic.
* Docker, Kubernetes, Terraform apply, k6 and GitHub CI were never executed.
* A cancel arriving during fulfillment is resolved by the state machine and compensation, not by a cross-service lock.

## Future enhancements (prioritised)

1. **Security hardening**: OIDC with RS256, per-service identities, mTLS or mesh, secret rotation, dependency pinning by digest.
2. **Reliability**: persist trace context through the outbox, row-claim the scheduled jobs, build a DLT replay tool, add `progressDeadlineSeconds`, run chaos tests (broker loss, DB failover).
3. **Scale**: keyset pagination, read replicas for reporting, partition planning, Redis caching with explicit invalidation, load-test baselines.
4. **Product**: payment and shipping adapters, partial fulfillment, backorders, multi-warehouse stock.
5. **Delivery**: GitOps (Argo CD or Flux), progressive delivery (canary), SBOM and image signing, preview environments.
6. **Frontend**: generate both UIs' types from `specs/openapi/*.json`, nonce-based Emotion CSP, `OnPush` or zoneless, Signal Forms when stable, accessibility audit, run the live-mode end-to-end suite in CI (it passed 18/18 locally against the real stack).
7. **Observability**: SLO burn-rate alerts, exemplars linking metrics to traces, structured business metrics per tenant.

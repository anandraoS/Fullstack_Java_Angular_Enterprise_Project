# Deployment and Rollback - OMS

Scope: how a release moves through dev, test and prod and how to back it out. Companion: `infrastructure/kubernetes/README.md`, `.github/workflows/ci.yml`, `docs/security/threat-model.md`.

## 0. Honest status

* The Kustomize base and overlays (`infrastructure/kubernetes/{base,overlays/dev,overlays/test,overlays/prod}`) render and pass `kubeconform -strict` (CRDs skipped). **Nothing has been applied to a cluster.** Probes, NetworkPolicies, PDB, HPA, KEDA and the dev-dependency StatefulSets are unverified at runtime.
* `ci.yml` has never run on a GitHub runner; its `deploy-test` job is a gated placeholder (skips when `KUBE_CONFIG_TEST_B64` is unset).
* The services being deployed (order, inventory, fulfillment, notification, gateway) are all implemented now and their jars exist (`services/*/target/<module>-1.0.0.jar`, `gateway/target/api-gateway-1.0.0.jar`, built by the 2026-10-18 reactor run) and run as JVMs in the local stack. **No Docker image was ever built** (no Docker) and no jar ran inside a container, so image names, Dockerfile layering and container flags remain unverified (`infrastructure/README.md`; `docs/known-limitations.md`).
* The prod overlay contains PLACEHOLDER endpoints (`oms-pg.cluster-PLACEHOLDER...`, `b-1.oms-msk.PLACEHOLDER...`). It is a reference, not a deployable environment.
* Procedures below that say "rehearse" (Sprint 4 story S11.2) were **not rehearsed**: no cluster was available, so they are untested as of 2026-10-18.

## 1. Environments and overlays

| Env | Overlay | Namespace | Replicas | Data stores | Profile / notable patches |
|---|---|---|---|---|---|
| local | none (scripts/run-local.sh) | n/a | 1 each | H2 + embedded Kafka (`local-stack`) | `local`; generates random `OMS_JWT_SECRET`, prints demo password |
| compose | `infrastructure/compose/docker-compose.yml` | n/a | 1 each | Postgres 16, Kafka KRaft, Redis | `${VAR:?}` makes start fail on unset secrets; ports bound to 127.0.0.1 |
| dev | `overlays/dev` | `oms-dev` | 1 (PDBs deleted so drains are not blocked; HPA 1-2) | `dev-dependencies/` in-cluster Postgres/Kafka/Redis (single replica, not HA, plaintext) | tracing sampling 1.0 |
| test | `overlays/test` | `oms-test` | base (2) | in-cluster `dev-dependencies/` | HPA max 3; sampling 0.5 |
| prod | `overlays/prod` | `oms-prod` | order/inventory/gateway 3, fulfillment/notification 2, frontend 2; HPA 3-12 | managed RDS / MSK / ElastiCache (placeholders); no in-cluster data | `SPRING_PROFILES_ACTIVE=prod,json-logs`; `DoNotSchedule` hostname spread plus zone spread; PDB `minAvailable: 2` for order/inventory/gateway; sampling 0.05 |

Finding to fix before first prod use: `base/configmap.yaml` sets `SPRING_PROFILES_ACTIVE: "dev,json-logs"`; dev and test inherit it (intended) but any new overlay that forgets to patch it would enable the dev token endpoint and seed data. Mitigation: prod patch exists; add a `validate-infra.sh` assertion that rendered prod has no `dev` profile (risk R-08).

## 2. Pipeline (design)

```
PR: java unit tests -> frontend lint/test/build -> CodeQL + Semgrep + Trivy fs + npm audit + gitleaks -> infra-validate
main: + java-integration (Testcontainers on Linux runner) -> docker build + Trivy image scan (fail on fixable HIGH/CRITICAL)
      -> push to GHCR -> smoke-local (embedded Kafka + services on runner) -> deploy-test (gated, environment: test)
tag v*: semver = tag; image tag <semver> (non-tag builds: <VERSION>-<sha8>)
prod: promote the SAME image digest that passed test; manual approval; set image by digest in overlays/prod
```
Versioning: single semver base in `/VERSION`; image tag `<semver>-<gitsha8>`.

## 3. Rolling update mechanics (from `base/*.yaml`)

| Mechanism | Setting | Why |
|---|---|---|
| Strategy | RollingUpdate `maxSurge: 1`, `maxUnavailable: 0` | capacity never drops during rollout |
| Startup probe | `/actuator/health/liveness`, 5 s period, 36 failures (3 min) | slow JVM + Flyway on first boot must not be killed by liveness |
| Readiness probe | `/actuator/health/readiness`, 10 s, 3 failures | traffic only to ready pods |
| Liveness probe | `/actuator/health/liveness`, 20 s, 3 failures | restart wedged JVM |
| preStop | `sleep 10` | endpoints/ingress drop the pod before SIGTERM |
| Graceful shutdown | `SERVER_SHUTDOWN=graceful`, `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=25s`, `terminationGracePeriodSeconds: 45` | finish in-flight requests, stop Kafka listeners (10 + 25 < 45) |
| History | `revisionHistoryLimit: 5` | allows `rollout undo` |
| PDB | `minAvailable: 1` base, 2 prod (order/inventory/gateway) | voluntary disruption safety |

### Readiness gates (what must be true before a pod receives traffic or the rollout advances)

1. Flyway migrations applied (app only reports ready after context start).
2. Readiness group UP. Decision: the readiness group does not include Kafka (verified in `services/order-service/src/main/resources/application.yml`: `readiness.include: readinessState,db`; the other services were not checked individually). Rationale: do NOT include Kafka in readiness (a broker blip would drain every pod and amplify the outage); expose it through `/api/v1/ops/messaging` and alerts instead.
3. Rollout verification beyond probes (manual or scripted): `GET /api/v1/ops/health` (gateway aggregate, OPERATOR token) shows all services UP; DLT depth unchanged; `oms.outbox.pending` not rising; error rate and p95 on Grafana "OMS - API RED"; consumer lag stable on "OMS - Kafka Messaging".
4. Soak: hold 10 minutes at 100 percent before declaring done; no canary tooling is configured (gap; Argo Rollouts/Flagger would be the extension).

## 4. Release procedure (per environment)

```bash
# 1. set images (CI does this; prod by digest)
cd infrastructure/kubernetes/overlays/<env>
kustomize edit set image ghcr.io/example-org/oms/order-service=<registry>/order-service:<semver>-<sha8>
# 2. render and validate offline
kubectl kustomize . | kubeconform -strict -ignore-missing-schemas -
# 3. server-side dry run, then apply
kubectl apply -k . --dry-run=server
kubectl apply -k .
# 4. watch
kubectl -n oms-<env> rollout status deploy/order-service --timeout=300s
```
Order of rollout for a release that changes messages or schema: (1) consumers first (inventory, fulfillment, notification, then order's listeners) because event schemas are additive and consumers are tolerant readers; (2) producers; (3) gateway; (4) frontend. Apply one Deployment at a time in prod and verify between steps.

Secrets (`oms-secrets`: `jwt-secret`, `<svc>-db-username|password`, `postgres-password`, `redis-password`, optional `demo-password` for dev) must exist before apply. dev/test: `infrastructure/kubernetes/examples/create-dev-secret.sh`. prod: External Secrets from AWS Secrets Manager (`examples/externalsecret.example.yaml`). Never commit Secret objects (`validate-infra.sh` checks no overlay renders one).

## 5. Database migrations: expand / contract

Flyway runs at service start (`classpath:db/common` + `classpath:db/migration`). During a rolling update old and new pods run against the same schema, so every migration must be backward compatible with the previous release.

| Phase | Release | Allowed | Forbidden in the same release |
|---|---|---|---|
| Expand | N | add table, add nullable/defaulted column, add index (PG: build concurrently in a separate manual step for large tables), add new enum value in code-tolerant way | dropping/renaming anything the N-1 code uses; adding NOT NULL without default |
| Migrate | N | dual-write code; backfill in batches (separate job, not inside the startup migration for large tables) | long locks |
| Contract | N+1 or later | after N-1 is fully gone and a rollback to N-1 is no longer planned: drop old column, add NOT NULL, remove dual-write | doing this together with expand |

Rules specific to this repo: V1..V8 only platform-common; service migrations V10+; seeds V1000+ and dev profile only; never edit an applied file (Flyway checksum failure); SQL must stay portable to H2 (PostgreSQL mode). Flyway has no automatic down-migrations here: rollback of schema means a forward "fix" migration, which is why expand/contract matters. Take a managed-DB snapshot before any contract-phase migration.

Multiple replicas starting at once: Flyway takes a lock, so one applies and the others wait; the startup probe window (3 min) must exceed the longest migration.

## 6. Kafka consumer concerns during deploy and rollback

| Concern | Behaviour / design | Action |
|---|---|---|
| Rebalance on every pod restart | consumer group = service name; 3 partitions; rolling one pod at a time keeps pauses short | keep `maxUnavailable: 0`; avoid restarting all pods at once |
| Max useful replicas | partitions = 3: more than 3 consumers per group sit idle | cap HPA/KEDA at 3 for consumers (note: base HPA allows 6, prod overlay 12; for consumer-heavy services prefer the KEDA example capped at 3 or accept idle pods) |
| In-flight message when pod is killed | uncommitted offset => redelivery; `processed_events` makes the business effect once | none, but verify `oms.events.duplicates` rises only briefly |
| New event type or field | consumers are tolerant readers; unknown `eventType` ignored and counted | deploy consumers before producers |
| Removing/renaming a field | breaks old consumers | forbidden within `schemaVersion` 1; use a new `schemaVersion` with a dual-publish period |
| Rollback after producing new events | old pods read new events (additive) | confirm `oms.events.processed` for unknown types is acceptable; if the new event carries data the old code needs, rollback is not safe (use fix-forward) |
| Poison message after deploy | 4 attempts, then `<topic>.DLT` | inspect via `GET /api/v1/ops/messaging` (`dltTopics`); replay after fix (**no replay tool exists**: replay is a manual procedure, `docs/known-limitations.md` item 3); DLT growth blocks the release gate |
| Outbox backlog | `OutboxRelay` retries in order; `oms.outbox.pending` gauge | if pending climbs after rollout, check broker reachability before rolling back app |
| Offsets reset | never reset a group in prod without incident approval (would replay; safe only because of idempotency, but expensive) | incident commander approval |
| Consumer group id change | creates a new group and re-reads from `auto.offset.reset` | never rename the service/group casually |

## 7. Rollback decision tree

```
Release is live and a signal fires
|
+-- Is there customer-visible harm (5xx, wrong data, stuck orders) or data corruption risk?
|     no  -> continue soak, open ticket, fix forward in next release
|     yes -> go on
|
+-- Is it data corruption / wrong business effect being written (oversell, wrong status, duplicate charges-equivalent)?
|     yes -> STOP INGRESS FIRST: scale gateway to 0 or set maintenance route; pause consumers (scale to 0);
|            declare incident (post-incident-review-template.md); then choose A or C below
|     no  -> go on
|
+-- Did this release include a migration?
|     no  -> A: kubectl rollout undo (fast, safe)
|     yes -> was it expand-only (additive)?
|              yes -> A: rollout undo; old code ignores new columns
|              no  -> contract/destructive: DO NOT undo blindly.
|                     restore snapshot only if data loss is acceptable; otherwise
|                     C: fix forward with a new migration + hotfix image
|
+-- Did new code publish new event versions that old code cannot process?
|     yes -> C: fix forward (old pods would dead-letter them)
|     no  -> A
|
+-- Is the problem configuration/secret only?
      yes -> B: revert the ConfigMap/Secret change (GitOps revert), restart pods, no image change

A: kubectl -n oms-<env> rollout undo deploy/<name>; rollout status; verify gateway ops health; check DLT, lag, outbox
B: revert commit that changed config; kubectl apply -k; kubectl rollout restart deploy/<name>
C: branch from the release tag, minimal fix + test, expedited pipeline, deploy by normal procedure
```

Post-rollback verification: `rollout history` shows previous revision; readiness green; `GET /api/v1/admin/orders/failed` (OPERATOR) reviewed for orders damaged during the bad window; run `POST /api/v1/admin/reconciliation/run` (ADMIN; implemented in `order/api/AdminController`, backed by `ReconciliationService`, tested in `ReconciliationTest`) to repair semantic inconsistencies; a rollback does not undo business effects already applied by the bad version.

## 8. Dev-to-prod differences and demo-only controls to remove

| Item | Demo / dev | Prod requirement |
|---|---|---|
| Profile | `dev` (token endpoint, seed data) | `prod,json-logs`, no dev token endpoint, no seed |
| JWT | HS256 shared secret | RS256/JWKS from an IdP (`oms.security.jwt.jwk-set-uri`) |
| Secrets | generated per run / `create-dev-secret.sh` | External Secrets or Sealed Secrets, rotation |
| Data stores | in-cluster, single replica, plaintext, no backups | RDS (Multi-AZ, backups, TLS), MSK (3 brokers, TLS/SASL), ElastiCache (auth token, TLS) |
| Network CIDR | `ipBlock 10.0.0.0/8` placeholder | tighten to data subnets |
| Images | version tag | digest-pinned, signed, scanned |
| Actions | major tags | pinned to commit SHA |
| Ingress/TLS | `*.example.test` hosts | real certificates (cert-manager), WAF |
| Prometheus endpoint | permitAll on service port in demo | management port restricted by NetworkPolicy |

## 9. Backout checklist (printable)

- [ ] Freeze further deploys; announce in the incident channel.
- [ ] Capture evidence: `kubectl get pods,events`, Grafana snapshot, DLT depth, outbox pending, lag.
- [ ] Decide A/B/C using the tree; record the decision and who approved.
- [ ] Execute; verify readiness and ops health.
- [ ] Reconcile data; list affected order ids.
- [ ] Open post-incident review within 2 working days.

# Release readiness checklist

Use before tagging a release. Deployment mechanics and rollback tree: `docs/governance/deployment-and-rollback.md`.

## Build and test gates
- [ ] CI green on the release commit: backend `mvn -B test` (reactor), frontend `ng test` + `ng build`, e2e where wired.
- [ ] Skipped tests listed (Docker-dependent Testcontainers) and run once on a Docker-capable runner before prod.
- [ ] Dependency scan and image scan reviewed (CI jobs under `.github/workflows/ci.yml`; do not claim results not seen).
- [ ] OpenAPI export and event schemas committed and diffed: no breaking change inside v1.

## Data
- [ ] Flyway migrations reviewed; expand/contract respected; backfill plan and duration estimate; rollback = previous
      image works against the new schema.
- [ ] Backups verified restorable before schema-changing releases (prod-only step; record who did it).

## Messaging
- [ ] New topics/partitions created (replication 3 prod); ACLs for producers/consumers.
- [ ] Consumer compatibility: new events additive; old consumers tolerate them; DLT empty or triaged.
- [ ] `oms.outbox.pending` near zero and consumer lag stable before starting.

## Security and config
- [ ] Secrets present in the target namespace (JWT secret >= 32 bytes, DB creds); no defaults; rotation date known.
- [ ] Profile `dev` (dev token endpoint, demo users, seed data) NOT active in prod.
- [ ] Network policies restrict actuator/prometheus and `/api/v1/internal/**`.

## Operability
- [ ] Dashboards and alerts (observability dir) cover new metrics; on-call has the runbooks and the release diff.
- [ ] Readiness/liveness probes pass in staging; rolling update `maxUnavailable=0` verified.
- [ ] Smoke test (`scripts/smoke-test.sh` or equivalent) passes in staging after deploy.

## Go / no-go
- [ ] Rollback owner and trigger conditions named (error rate, p99, lag, DLT growth).
- [ ] Post-deploy watch window scheduled; post-incident template ready (`docs/governance/post-incident-review-template.md`).

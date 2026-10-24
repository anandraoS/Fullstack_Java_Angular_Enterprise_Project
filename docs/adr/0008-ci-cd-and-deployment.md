# ADR-0008: CI/CD pipeline and deployment model

* Status: Accepted
* Date: 2026-10
* Deciders: platform architecture

## Context

Four JVM services, a gateway and an Angular UI must be built, tested, scanned, containerized and deployed repeatably. The dev machine has no Docker, so much of the infrastructure can only be validated statically.

## Decision drivers

* Fast feedback: unit tests on every push, slower checks in parallel jobs.
* Supply-chain hygiene: dependency, secret, SAST and image scanning before publishing.
* Declarative, reviewable deployment with environment overlays.
* Honesty about what is verified and what is not.

## Considered options

1. GitHub Actions plus Kustomize overlays, GHCR images (chosen), with a Jenkinsfile mirror.
2. Helm charts.
3. GitOps controller (Argo CD, Flux) as the only deployer.
4. Single "build everything and deploy" script.

## Decision outcome

`.github/workflows/ci.yml` jobs: `version`, `java-build-test` (unit tests, jars), `java-integration` (failsafe), `frontend`, `codeql`, `semgrep`, `dependency-scan` (Trivy filesystem plus `npm audit`), `secret-scan` (gitleaks), `infra-validate` (kustomize build, kubeconform, terraform validate, compose config, observability checks), `docker` and `docker-ui` (build, Trivy image scan failing on fixable HIGH/CRITICAL, push to GHCR on main and tags only), `smoke-local` (embedded Kafka plus 4 services plus gateway on H2), and `deploy-test` (guarded by a GitHub `environment: test` with required reviewers; secrets are environment-scoped; a placeholder step documents what is needed). `infrastructure/ci/Jenkinsfile` mirrors the stages for teams on Jenkins.

Deployment: Kustomize base in `infrastructure/kubernetes/base` with `overlays/dev`, `overlays/test`, `overlays/prod`; network policies, KEDA Kafka-lag scaling (`infrastructure/kubernetes/keda`), monitoring rules (`infrastructure/kubernetes/monitoring`). Images are built per service from `infrastructure/docker/*.Dockerfile`. AWS reference infrastructure is Terraform under `infrastructure/cloud/aws/terraform` (EKS, ECR, network, secrets). Database migrations run via Flyway at service start (ADR-0004); expand-then-contract is required so old and new versions coexist during rolling updates. Rollback is `kubectl rollout undo` or re-deploying the previous image tag; schema rollbacks are forward-fixes, never down-migrations.

Honest status: the pipeline YAML, Kubernetes manifests, Compose files and Terraform are statically validated (parsing, kustomize build, schema checks). They have not been executed against a real cluster or cloud account from the dev machine. Testcontainers-based tests are annotated `disabledWithoutDocker = true` and are skipped locally; they run only where Docker is available (CI runners).

### Consequences

* Good: parallel gates, immutable image tags, reviewable overlays, no secrets in the repo, a deliberate approval gate before deploy.
* Bad: Kustomize has no templating power or release history (Helm would); two CI definitions can drift; no automated canary or progressive delivery yet; the deploy job is a placeholder until a real non-production cluster exists; first real deployment will likely surface issues static checks cannot see.

## Alternatives rejected

* Helm: richer, but values-file sprawl for a 5-component system; Kustomize is enough and renders to plain YAML for review.
* GitOps-only: the right production end state, deferred; the current job structure can feed it by committing image tags.
* Single script: no parallelism, no per-gate reporting.

## Links

* `.github/workflows/ci.yml`, `infrastructure/ci/Jenkinsfile`, `infrastructure/kubernetes`, `infrastructure/compose`, `infrastructure/docker`, `infrastructure/cloud`
* ADR-0010 (secrets), ADR-0011 (monitoring manifests)

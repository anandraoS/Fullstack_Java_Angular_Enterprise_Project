# CI/CD: Pipelines, Environments, Rollback and Supply-Chain Safety

This chapter is about how code gets from a developer's laptop to a running, observable, reversible release, using the pipeline files that actually exist in this repository: `.github/workflows/ci.yml`, its Jenkins mirror `infrastructure/ci/Jenkinsfile`, the Dockerfiles in `infrastructure/docker/`, the Kustomize base and overlays in `infrastructure/kubernetes/`, and the written release procedure in `docs/governance/deployment-and-rollback.md`. One fact must be said before anything else, because interviewers reward candidates who say it first: **the pipeline has never run.** The first line of `ci.yml` reads "NOT EXECUTED (authored offline; no GitHub runner was available). The YAML parses; behaviour is unverified." The same is true of the Jenkinsfile, the Kubernetes manifests (statically validated with `kustomize build` and `kubeconform` only) and the Terraform (validated, never applied). Everything below is therefore a design you can explain and defend, plus an honest list of places where a first real run is likely to surprise you.

## Big picture and kid-friendly analogy

Picture a chocolate factory.

* Ingredients arrive at the loading dock (a developer pushes code). A conveyor belt carries them past stations. At station one a taster checks that every single chocolate tastes right (unit tests). At station two a machine checks that the chocolates still fit together in the box (integration tests). A third station looks for broken glass and mouldy ingredients (security and dependency scanners). Only chocolates that pass every station get wrapped and sealed in a box with a serial number printed on the lid (a container image with an immutable version tag).
* The sealed box is never opened again. The same box that the tasters approved is the box that goes to the shop. If you re-wrapped it for each shop, you could never be sure what was inside (this is "build once, promote the same artifact").
* The delivery trucks go first to a practice shop (the test environment), and only after the shop manager signs a form (a manual approval gate) to the real shops (production).
* If customers complain about a new recipe, the shop does not cook a fix on the spot. It puts the previous box back on the shelf (rollback) and the kitchen works on the fix calmly (fix forward).
* Safer ways to launch a new recipe: open a second, identical shop with the new recipe and move customers over all at once (blue/green), or give the new chocolate to one customer in twenty first and watch their faces (canary).
* The key to the chocolate vault is never taped to the factory door. Workers get a temporary badge that expires (short-lived credentials, OIDC) rather than a permanent key.

| Analogy | Technical concept |
|---|---|
| Conveyor belt with stations | CI pipeline stages (build, test, scan, package) |
| Taster per chocolate | Unit tests, fast and on every push |
| Glass and mould check | SAST, dependency scan, secret scan, image scan |
| Sealed numbered box | Immutable image tag or digest |
| Same box to every shop | Build once, promote the same digest |
| Practice shop, then real shop | dev/test/prod environments and overlays |
| Manager's signature | GitHub `environment` with required reviewers |
| Put the old box back | `kubectl rollout undo` |
| Second identical shop | Blue/green deployment |
| One customer in twenty | Canary release |
| Temporary badge | OIDC federation, short-lived tokens, External Secrets |

## Beginner tier

### Example 1: the pipeline is a graph of jobs, and unit tests come first

`ci.yml` defines these jobs: `version`, `java-build-test`, `java-integration`, `frontend`, `codeql`, `semgrep`, `dependency-scan`, `secret-scan`, `infra-validate`, `docker` (a matrix over five services), `docker-ui`, `smoke-local` and `deploy-test`. Jobs without a `needs:` line start in parallel; jobs with `needs:` wait. The cheapest, fastest check (unit tests) is the root of most of the graph, which is the "fail fast" principle: a typo should be reported in minutes, not after a 20-minute image build.

```yaml
  java-build-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "17", cache: maven }
      - name: Unit tests
        run: mvn -B -ntp -f services/pom.xml test
      - name: Package jars (tests already ran)
        run: mvn -B -ntp -f services/pom.xml package -DskipTests
```

Points worth saying out loud in an interview. `-B` (batch mode) and `-ntp` (no transfer progress) keep CI logs readable. `cache: maven` stores the local repository between runs, which is the single biggest speed win for a Java build. Tests run once and packaging uses `-DskipTests`, so the build does not pay for tests twice. Java 17 is a pinned input (`JAVA_VERSION: "17"` in the workflow `env`), matching the Java 17 toolchain in `services/pom.xml` (`<java.version>17</java.version>`). The trade-off: surefire only runs `*Test` and `*Tests` classes (`services/pom.xml`, pluginManagement), so everything named `*IT` waits for the next job.

### Example 2: every artifact gets a version you can trace back to a commit

A pipeline that cannot answer "which commit is running in production?" is a liability. The `version` job computes one value and every later job consumes it through `needs.version.outputs`.

```yaml
          base="$(tr -d '[:space:]' < VERSION)"                    # e.g. 1.0.0 (the single source of the semver base)
          sha="$(git rev-parse --short=8 HEAD)"
          if [[ "${GITHUB_REF}" == refs/tags/v* ]]; then semver="${GITHUB_REF#refs/tags/v}"; else semver="${base}-${sha}"; fi
          echo "semver=${semver}" >> "$GITHUB_OUTPUT"
          echo "image_tag=${semver}" >> "$GITHUB_OUTPUT"
```

A tag such as `v1.2.0` produces the clean release version `1.2.0`; every other build is `1.0.0-<sha8>`. The `/VERSION` file holds the base. The Jenkinsfile repeats the same logic in its `Version` stage (`env.SEMVER = (env.TAG_NAME?.startsWith('v')) ? ... : "${base}-${sha}"`), which is also the first drift risk of keeping two CI definitions. Combine the version with the image labels the `docker` job sets (`org.opencontainers.image.revision=${{ github.sha }}`) and you can go from a running pod to a commit in two commands.

A third beginner point: **triggers**. The workflow runs on `push` to `main`, on tags `v*`, on `pull_request` to `main` and manually (`workflow_dispatch`). That is a trunk-based shape (see the Advanced tier): everything merges to `main` through pull requests, and a tag marks a release.

## Intermediate tier

### Example 1: gates, least privilege and concurrency control

Three small settings at the top of `ci.yml` carry a lot of engineering judgement.

```yaml
# Least privilege by default; jobs that need more declare it explicitly.
permissions:
  contents: read

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true
```

`permissions: contents: read` makes the automatic `GITHUB_TOKEN` read-only for every job, and only `codeql` (`security-events: write`), `docker` and `docker-ui` (`packages: write`) and `deploy-test` (`id-token: write`) widen it. If a compromised third-party action runs in `frontend`, the token it sees can read the repository and nothing else. `concurrency` cancels an older run of the same branch when a newer push arrives, saving runner minutes. Be ready to criticise it, though: `cancel-in-progress: true` also applies to `main`, so two quick merges can cancel the first one's image push. A mature setup scopes cancellation to pull-request refs only.

The security jobs are separate, parallel gates rather than steps hidden inside the build, so a failing scanner is reported by name:

| Job | Tool | What it catches |
|---|---|---|
| `codeql` | CodeQL (Java and TypeScript matrix) | Injection, unsafe deserialization, data-flow bugs |
| `semgrep` | Semgrep `p/default`, `p/java`, `p/typescript` with `--error` | Rule-based anti-patterns |
| `dependency-scan` | Trivy filesystem scan plus `npm audit --omit=dev` | Known CVEs in Maven and npm lockfiles, IaC misconfiguration |
| `secret-scan` | gitleaks with `fetch-depth: 0` | Credentials committed anywhere in history |
| `infra-validate` | `scripts/validate-infra.sh` | Kustomize renders, kubeconform schemas, Terraform validate, compose checks |

Note `fetch-depth: 0` on `secret-scan`: a shallow clone would only scan the tip commit and miss a secret that was added and then deleted. Note also the Trivy flags `severity: HIGH,CRITICAL`, `ignore-unfixed: true`, `exit-code: "1"`: the build fails only for serious findings that have a fix available, which keeps the gate actionable rather than permanently red. The release-level Definition of Done (`docs/governance/definition-of-ready-and-done.md`) repeats the policy: "No unresolved HIGH/CRITICAL scanner findings with an available fix."

### Example 2: building one hardened image per service, scanning it, and pushing only from `main`

Each service has its own Dockerfile (`infrastructure/docker/order-service.Dockerfile` and four siblings). The Dockerfile is multi-stage and layered so that dependencies (rarely changing) and application code (often changing) are separate image layers.

```dockerfile
FROM ${JRE_IMAGE} AS extract
ARG JAR_FILE=services/order-service/target/order-service-1.0.0.jar
WORKDIR /build
COPY ${JAR_FILE} app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ---- stage 2: minimal runtime
FROM ${JRE_IMAGE} AS runtime
...
USER 10001:10001
ENV SERVER_PORT=${APP_PORT} \
    SPRING_PROFILES_ACTIVE=json-logs \
    SERVER_SHUTDOWN=graceful \
    SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=25s
```

The runtime stage uses a JRE (not a JDK), runs as a fixed numeric non-root user so Kubernetes `runAsNonRoot` can verify it, enables graceful shutdown and uses an `exec` entrypoint so the JVM is PID 1 and receives SIGTERM directly. The file's own header admits the base tag `eclipse-temurin:17.0.13_11-jre-alpine` was "NOT digest-resolved here (no registry access during authoring)".

In the workflow the `docker` job builds with `load: true, push: false`, scans the local image with Trivy, and only then logs in and pushes, guarded by `if: github.ref == 'refs/heads/main' || startsWith(github.ref, 'refs/tags/v')`. The order matters: **scan before publish**, so a vulnerable image never reaches the registry where someone could pull it. The `strategy.matrix.service` list fans the job out to five parallel image builds, and `fail-fast: false` lets the other four finish when one fails so you see all problems at once.

### Example 3: environments as Kustomize overlays

Kubernetes configuration is a shared `base/` plus three thin overlays. The prod overlay (`infrastructure/kubernetes/overlays/prod/kustomization.yaml`) shows what differs between environments, and nothing else:

```yaml
namespace: oms-prod
resources:
  - ../../base
images:
  - { name: ghcr.io/example-org/oms/order-service, newTag: 1.0.0 }
replicas:
  - { name: order-service, count: 3 }
  - { name: api-gateway, count: 3 }
patches:
  - target: { kind: ConfigMap, name: oms-config }
    patch: |-
      - { op: replace, path: /data/SPRING_PROFILES_ACTIVE, value: "prod,json-logs" }
      - { op: replace, path: /data/MANAGEMENT_TRACING_SAMPLING_PROBABILITY, value: "0.05" }
```

The environment matrix from `docs/governance/deployment-and-rollback.md`: dev runs one replica per service with the pod disruption budgets deleted (single replicas plus a PDB would block node drains) and tracing sampling at 1.0; test uses the base replica count and sampling 0.5; prod runs three replicas for order, inventory and gateway, uses managed Postgres, Kafka and Redis (all endpoints are `PLACEHOLDER` strings), sets the profile to `prod,json-logs` and samples 5 percent of traces. The profile patch is security-critical: the base ConfigMap defaults to `dev,json-logs`, which would enable the development token endpoint and seed data. The deployment document calls this out as risk R-08 and proposes a `validate-infra.sh` assertion that rendered prod contains no `dev` profile. Kustomize versus Helm is ADR-0008's explicit trade-off: Kustomize renders plain YAML a reviewer can read, but it has no templating power and no release history.

## Advanced tier

### Example 1: rollout mechanics, rollback and expand/contract migrations

A deployment is safe only if you can describe, for each failure, how you go back. The base Deployment (`infrastructure/kubernetes/base/order-service.yaml`) sets the mechanics:

```yaml
  revisionHistoryLimit: 5
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1            # one extra pod during rollout
      maxUnavailable: 0      # never drop below desired capacity (zero-downtime)
```

With `maxUnavailable: 0` a new pod must become Ready before an old one is removed. Readiness uses `/actuator/health/readiness`, a startup probe allows three minutes for a slow JVM plus Flyway on first boot, a `preStop` sleep lets the endpoint drop before SIGTERM, and `terminationGracePeriodSeconds: 45` exceeds `10 + 25` (sleep plus Spring's graceful-shutdown phase timeout). `revisionHistoryLimit: 5` is what makes `kubectl rollout undo` possible.

The deployment document's decision tree is the part worth memorising. Roll back (A) when there was no migration or it was additive; revert configuration (B) when only a ConfigMap or Secret changed; **fix forward (C)** when a destructive migration ran or when the new code published event versions the old code cannot process. Before choosing, if the release is writing wrong business data (oversell, wrong status), stop ingress first and pause consumers. A rollback does not undo business effects the bad version already applied, so reconciliation follows.

This is why migrations must follow expand/contract. During a rolling update old and new pods share one schema, so release N may only add (nullable columns, new tables, indexes), release N+1 may drop. Flyway has no automatic down migrations in this repo, so "schema rollback" means a forward fix. Event schemas get the same discipline: additive within `schemaVersion` 1, consumers deployed before producers, consumers tolerant of unknown types.

**Blue/green versus canary versus rolling.** Rolling (what is configured) replaces pods gradually and has no second environment, so rollback is a second rolling update. Blue/green keeps two full environments and flips traffic at the ingress or service selector: fast, clean rollback, double the capacity cost, and awkward with a shared database (both colours must accept the same schema, which is expand/contract again). Canary sends a small percentage of traffic to the new version and promotes on metrics. The repository is candid: "no canary tooling is configured (gap; Argo Rollouts/Flagger would be the extension)" and the soak step is "hold 10 minutes at 100 percent". Be able to say that a canary decision needs the alerts from the observability chapter (error rate and p95 per version) or it is just a slower rolling update.

### Example 2: secrets in pipelines and a supply chain you can defend

Rule one: no secret in the repository, the workflow file or a log. The repository applies it at several layers.

```yaml
      - name: Start local stack (embedded Kafka + 4 services + gateway, H2)
        run: |
          export OMS_JAVA_HOME="${JAVA_HOME}"
          # credentials are generated per run and written (mode 600) OUTSIDE the workspace, never echoed to the log
          bash scripts/run-local.sh --no-build --emit-env "${RUNNER_TEMP}/oms.env"
```

`run-local.sh` generates a random JWT secret and demo password per run, passes them to child JVMs by environment only, and with `--emit-env` writes them to a mode-600 file in `RUNNER_TEMP`. The Kubernetes side never stores Secret objects: manifests use `secretKeyRef` against `oms-secrets`, and `validate-infra.sh` checks that no overlay renders a Secret. For production the example `infrastructure/kubernetes/examples/externalsecret.example.yaml` shows External Secrets syncing from AWS Secrets Manager by key name only:

```yaml
spec:
  refreshInterval: 1h
  secretStoreRef:
    name: aws-secrets-manager
    kind: ClusterSecretStore
  target:
    name: oms-secrets
    creationPolicy: Owner
  data:
    - { secretKey: jwt-secret, remoteRef: { key: oms/prod/jwt, property: secret } }
```

The deploy job requests `id-token: write` so a cloud login could use OIDC federation (a short-lived token minted per run) instead of a stored access key. The placeholder deploy step still uses a base64 kubeconfig secret named `KUBE_CONFIG_TEST_B64` scoped to a protected `environment: test`, which is the honest weaker option and is labelled as such.

Supply-chain controls present: CodeQL, Semgrep, Trivy on source and on images, gitleaks over full history, `npm ci --ignore-scripts` (blocks install-time scripts from dependencies), a checksum-verified tool downloader (`scripts/fetch-validation-tools.mjs`), and Terraform ECR repositories with `image_tag_mutability = "IMMUTABLE"` and `scan_on_push = true` (`infrastructure/cloud/aws/terraform/ecr.tf`). Controls named but not done: the top of `ci.yml` says to "pin every `uses:` to a full commit SHA", because tags are mutable and a hijacked `actions/checkout@v4` tag runs with your token; images are not signed (no cosign), there is no SBOM and no provenance attestation, and the prod overlay uses `newTag` rather than a digest. The threat model scores this honestly as OWASP A08 "partial".

### Example 3: trunk-based development, GitOps and what the repo does instead

**Trunk-based development**: short-lived branches (hours to a couple of days) merged to `main` behind pull-request checks; unfinished work hides behind feature flags; releases are tags on `main`. **Gitflow**: long-lived `develop`, `release/*` and `hotfix/*` branches. The repository's triggers (`push: [main]`, `tags: v*`, PRs to `main`) are trunk-based, which suits continuous delivery and a small team; gitflow fits scheduled, versioned products with parallel supported releases, at the price of painful merges and late integration. The hotfix path in the deployment document (branch from the release tag, minimal fix plus test, expedited pipeline) is the one gitflow-like concession.

**GitOps** means the desired state of the cluster lives in Git and an in-cluster controller (Argo CD or Flux) pulls and reconciles it. Rollback becomes `git revert`, drift is detected and corrected, and CI never holds cluster credentials. ADR-0008 lists GitOps as an option "deferred": the current job pushes with `kustomize edit set image` and `kubectl apply` from CI (push-based), which means the cluster credential lives in CI and the changed image tag is never committed, so Git does not describe what is running. The ADR notes the existing jobs "can feed it by committing image tags", which is the natural migration.

## How this repository implements it

| Concept | Where in repo | Notes |
|---|---|---|
| Pipeline definition | `.github/workflows/ci.yml` | 13 jobs; never executed on a runner |
| Jenkins equivalent | `infrastructure/ci/Jenkinsfile` | Declarative, parallel gates, `input` approval, not parsed by Jenkins |
| Versioning | `VERSION`, `version` job, Jenkins `Version` stage | semver base plus sha8 |
| Unit and integration split | `services/pom.xml` surefire `*Test`, failsafe `*IT` | failsafe declared per service module |
| Coverage | `jacoco-maven-plugin` in `services/pom.xml` | Reports only, no threshold |
| Security scanning | `codeql`, `semgrep`, `dependency-scan`, `secret-scan` jobs | Tag-pinned actions |
| Image build | `infrastructure/docker/*.Dockerfile`, `frontend/oms-ui/Dockerfile` | Multi-stage, non-root, layered jar |
| Image registry | GHCR in CI; ECR in `infrastructure/cloud/aws/terraform/ecr.tf` | Immutable tags and scan on push in Terraform |
| Environments | `infrastructure/kubernetes/overlays/{dev,test,prod}` | Prod endpoints are placeholders |
| Rollout and rollback | `infrastructure/kubernetes/base/*.yaml`, `docs/governance/deployment-and-rollback.md` | Rolling update, decision tree A/B/C |
| Secrets | `infrastructure/kubernetes/examples/`, `scripts/run-local.sh` | No Secret objects in overlays |
| Autoscaling | `infrastructure/kubernetes/keda/scaledobject-kafka-lag.yaml` | Max replicas capped at partitions (3) |
| Infra validation | `scripts/validate-infra.sh`, `scripts/fetch-validation-tools.mjs` | PASS/FAIL/SKIP reported separately |
| Decision record | `docs/adr/0008-ci-cd-and-deployment.md` | Documents rejected Helm and GitOps-only |

### Demonstration-only vs production-grade

Production-grade ideas: least-privilege token permissions, parallel gates, scan-before-push, non-root minimal images, graceful shutdown that matches probe and grace-period arithmetic, expand/contract migration policy, a written rollback decision tree, secrets by reference only, immutable ECR tags. Demonstration-only or unverified: the whole workflow has not run; `deploy-test` is a gated placeholder that skips when `KUBE_CONFIG_TEST_B64` is unset; actions are pinned by tag; images are neither digest-pinned nor signed; no canary or blue/green tooling; no GitOps controller; Playwright and k6 are not wired into CI; no JaCoCo gate; the prod overlay is a reference with placeholder hosts; and deployment and rollback rehearsal is scheduled (story S11.2) but not done. The docs also state that Testcontainers-based tests are skipped locally and only run where Docker exists, which is the CI runner, so the first green CI run is the first time those tests would execute.

## Common mistakes and gotchas

* **Claiming the pipeline works.** It parses; it has not run. Say "authored and statically validated" and name what a first run will exercise.
* **`mvn verify` re-runs unit tests.** `java-integration` runs `mvn verify -DskipTests=false`; surefire runs again before failsafe, so unit tests execute twice in the pipeline. The `-DskipTests=false` flag is also a no-op. A cleaner split is `-DskipTests` plus failsafe, or `-Dsurefire.skip`.
* **Hard-coded jar names.** The upload glob `services/*/target/*-1.0.0.jar`, the Dockerfile `JAR_FILE` default and the Jenkins `archiveArtifacts` all contain `1.0.0` while the version is meant to come from `/VERSION`. Bumping the version breaks packaging in three places.
* **Image-name mismatch.** The `docker` job tags `ghcr.io/${{ github.repository }}/<svc>`, but the overlays and the deploy step's `kustomize edit set image` use `ghcr.io/example-org/oms/<svc>`. A real run needs one agreed registry path.
* **Deploy job assumes tools.** `deploy-test` calls `kustomize` and `kubectl` without installing or pinning them, while the tools the repo trusts (checksum-verified) are fetched only in `infra-validate`.
* **Browser flag mismatch.** `package.json` runs Karma with `ChromeHeadlessNoSandbox`, but CI overrides it with `--browsers=ChromeHeadless`; Chrome sandboxing in containers is a classic first-run failure.
* **`cancel-in-progress` on main** can cancel a release build; restrict it to pull requests.
* **Mutable tags.** `latest`, major-version action tags and unpinned base images make a build non-reproducible and widen the attack surface.
* **Scanning only the tip.** A shallow checkout misses secrets removed in later commits; rotate any secret that was ever committed, deleting it is not enough.
* **Treating a skipped test as green.** Report skipped counts. In this repo the local failsafe summaries on disk show skipped Postgres tests (for example inventory-service: 32 completed, 18 skipped), which is exactly why CI must run them.
* **Forgetting data in rollback.** `rollout undo` cannot undo a destructive migration or a business effect; know the fix-forward path before deploying.
* **Two pipelines drifting.** The Jenkinsfile mirrors `ci.yml`; one of them will be edited alone unless a check compares them or one is generated from the other.

## Quick-recall cheat sheet

* Order of stages: version, build and unit test, parallel gates (integration, frontend, SAST, deps, secrets, infra), image build, image scan, push (main and tags only), smoke, gated deploy.
* Build once, promote the same digest; tag = `<VERSION>-<sha8>` or the release tag.
* Fail fast: cheapest checks first, expensive ones after; scan before publish.
* `permissions: contents: read` by default; widen per job; OIDC beats stored keys.
* Overlays change replicas, images, profile, endpoints and sampling, nothing else; `prod,json-logs` must never be `dev`.
* Rolling update: `maxSurge 1`, `maxUnavailable 0`, readiness gate, preStop sleep, grace period larger than shutdown work.
* Rollback tree: no migration or additive migration, roll back; config only, revert config; destructive migration or incompatible events, fix forward; corruption, stop ingress first.
* Expand then contract; consumers before producers; no down migrations.
* Blue/green: instant switch, double cost; canary: small slice plus metrics; rolling: gradual, no second environment.
* GitOps: Git is the source of truth, controller pulls; current repo is push-based.
* Honest status: not executed on GitHub, no cluster, tag-pinned actions, no signing, no canary.

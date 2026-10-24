# Docker, Compose and Kubernetes: Images, Probes, Scaling, Network Policy and Kustomize Overlays

This chapter covers containers and orchestration as they appear in `infrastructure/` and in `frontend/oms-ui/Dockerfile`. One status line governs everything below and is repeated on purpose, because interviewers reward candidates who separate "written" from "proven": **Docker was not installed on the development machine, so no image in this repository was built and no Compose stack was run. The Kubernetes manifests were rendered with `kustomize build` and checked with `kubeconform -strict` (core kinds; CRDs skipped), and nothing was applied to a cluster.** That is stated in `infrastructure/README.md`, `infrastructure/kubernetes/README.md`, `docs/governance/deployment-and-rollback.md` and the header comments of the Dockerfiles and Compose file. No the docs/validation folder directory exists in the repository; the validation evidence is the script `scripts/validate-infra.sh`, which prints PASS, FAIL or SKIP per check and counts skips separately.

## Big picture and kid-friendly analogy

Imagine a food court at a big shopping mall.

* **A Docker image** is a sealed lunchbox recipe card plus all the ingredients, frozen. **A container** is one lunchbox being served right now. Ten lunchboxes from the same card are identical.
* **Layers** are the stack of cardboard dividers in the lunchbox: rice at the bottom (rarely changes), curry on top (changes often). If only the curry changes, the cook re-makes only the curry layer. That is why you copy the dependency list before your source code.
* **A multi-stage build** is cooking in a messy kitchen but serving from a clean counter. You throw away the pots and peelings (compilers, build tools) and hand over only the finished dish.
* **Running as non-root** is giving the lunchbox server an apron with no keys to the cash register.
* **A health check** is the manager tapping each counter and asking "are you open?" and, separately, "are you ready for the lunch rush?".
* **Kubernetes** is the food-court manager. You tell the manager "I always want three curry counters open"; if one catches fire the manager opens another. A **Deployment** is that standing order. A **Service** is the single sign pointing hungry people to whichever curry counter is free. A **ConfigMap** is the printed menu everyone may read; a **Secret** is the safe combination only staff may see.
* **Readiness vs liveness**: readiness is "do not send customers yet, still setting up"; liveness is "this counter is frozen, replace it".
* **HPA** is the manager opening more counters when the queue grows; **KEDA** is opening more counters when the kitchen's order tickets pile up, not when the cooks look tired.
* **A NetworkPolicy** is the food court's rule that the dessert stall may only talk to the freezer, never to the cash office.
* **Kustomize overlays** are the same menu with local variations: the small branch has one counter and the flagship has three, same recipes.
* **A rolling update** is renovating counters one at a time while the others keep serving.

| Analogy | Concept |
|---|---|
| Recipe card plus frozen ingredients | Image |
| Lunchbox being served | Container |
| Cardboard dividers | Image layers, build cache |
| Messy kitchen, clean counter | Multi-stage build |
| Apron with no keys | Non-root user, dropped capabilities |
| "Open?" and "ready for the rush?" | Liveness and readiness probes |
| Standing order for counters | Deployment, ReplicaSet |
| Menu vs safe | ConfigMap vs Secret |
| Open more counters | HPA / KEDA |
| Stall-to-stall rules | NetworkPolicy |
| Same menu, local variations | Kustomize base and overlays |
| Renovate one counter at a time | Rolling update, `maxUnavailable: 0` |

## Beginner tier

### Example 1: a layered, multi-stage, non-root Java image

All five Java images share one shape. `infrastructure/docker/order-service.Dockerfile`:

```dockerfile
ARG JRE_IMAGE=eclipse-temurin:17.0.13_11-jre-alpine

# ---- stage 1: split the Spring Boot fat jar into cache-friendly layers (deps rarely change, app code often)
FROM ${JRE_IMAGE} AS extract
ARG JAR_FILE=services/order-service/target/order-service-1.0.0.jar
WORKDIR /build
COPY ${JAR_FILE} app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ---- stage 2: minimal runtime
FROM ${JRE_IMAGE} AS runtime
ARG APP_PORT=18081
# Non-root, fixed numeric uid/gid so Kubernetes runAsNonRoot can verify it. No shell login, no home.
RUN addgroup -S -g 10001 oms && adduser -S -u 10001 -G oms -H -s /sbin/nologin oms \
    && mkdir -p /app && chown -R oms:oms /app
WORKDIR /app
COPY --from=extract --chown=oms:oms /build/extracted/dependencies/ ./
COPY --from=extract --chown=oms:oms /build/extracted/spring-boot-loader/ ./
COPY --from=extract --chown=oms:oms /build/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=oms:oms /build/extracted/application/ ./

USER 10001:10001
```

What to explain. The Maven build (outside Docker, `mvn -f services/pom.xml package`) produces the fat jar; the Dockerfile only copies it in. Stage one uses Spring Boot 3.3+'s `-Djarmode=tools ... extract --layers` to split the jar into four directories ordered from least to most frequently changing: `dependencies`, `spring-boot-loader`, `snapshot-dependencies`, `application`. Each `COPY` becomes an image layer, so a code-only change re-pushes a small layer instead of the whole dependency set; registries and nodes cache the unchanged ones. Stage two starts from the JRE only (`-jre-alpine`, no JDK, no Maven, no source), so the final image has a smaller attack surface. The user is a numeric `10001:10001`, which matters because Kubernetes `runAsNonRoot: true` cannot verify a symbolic user name. The base tag is pinned to an exact build rather than `latest`; the comment in the file is honest that it is not pinned by digest ("The tag was NOT digest-resolved here (no registry access during authoring)").

The root `.dockerignore` is a whitelist. It starts with `*` (ignore everything) and re-includes only the jars:

```
*
!services/*/target/*-1.0.0.jar
!gateway/target/*-1.0.0.jar
```

A tiny build context sends faster and cannot accidentally copy `.env`, `.git` or credentials into a layer. The frontend has its own `frontend/oms-ui/.dockerignore` (`node_modules`, `dist`, `.angular`, `coverage`).

### Example 2: container-level health check, JVM flags and graceful shutdown

The runtime half of the same Dockerfile:

```dockerfile
ENV SERVER_PORT=${APP_PORT} \
    SPRING_PROFILES_ACTIVE=json-logs \
    SERVER_SHUTDOWN=graceful \
    SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=25s \
    TZ=UTC \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC -Djava.io.tmpdir=/tmp -Djava.security.egd=file:/dev/urandom -Dfile.encoding=UTF-8"
EXPOSE 18081
# Container-level healthcheck (Compose/Docker). Kubernetes ignores HEALTHCHECK and uses its own probes.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD wget -qO- http://127.0.0.1:${SERVER_PORT}/actuator/health/readiness | grep -q '"status":"UP"' || exit 1
# SIGTERM -> JVM shutdown hook -> Spring graceful shutdown (drains in-flight HTTP, stops Kafka listeners).
STOPSIGNAL SIGTERM
# 'exec' makes the JVM PID 1 so it receives SIGTERM directly.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
```

Four points that interviewers probe. **Memory flags**: the JVM sizes its heap from the container's cgroup limit when `UseContainerSupport` is on (default in modern JDKs); `MaxRAMPercentage=75.0` leaves headroom for metaspace, thread stacks and direct buffers, which is the usual cause of OOM kills when `-Xmx` equals the container limit. `ExitOnOutOfMemoryError` makes a dying JVM exit so the orchestrator restarts it instead of leaving a half-dead process. **`exec` and PID 1**: with a shell form entrypoint the shell is PID 1 and may swallow SIGTERM; `exec java` replaces the shell with the JVM so the signal reaches the shutdown hook, then Spring's graceful shutdown (`server.shutdown=graceful`, 25 s per phase) drains in-flight requests and stops Kafka listeners. **HEALTHCHECK** is a Docker and Compose feature only; Kubernetes ignores it, which the comment states, and uses its own probes (Example 1 of the intermediate tier). **Frontend image**: `frontend/oms-ui/Dockerfile` is a two-stage build, `node:24-alpine` runs `npm ci` and `ng build --configuration production`, then `nginxinc/nginx-unprivileged:1.27-alpine` serves `dist/oms-ui/browser` on port 8080 as uid 101, with `envsubst` generating `default.conf` from `frontend/oms-ui/nginx.conf` so `API_GATEWAY_URL` and `OMS_API_BASE_URL` are runtime settings. Its header comment says: "NOT built in the development environment of this repository (Docker is not installed there): treat as untested."

## Intermediate tier

### Example 1: Compose for local, Deployment and probes for the cluster

`infrastructure/compose/docker-compose.yml` runs the whole stack on one machine (PostgreSQL 16.6, Kafka 3.9.1 in KRaft mode, Redis 7.4, four services, the gateway and the UI). It uses YAML anchors to apply hardening to every Java service once:

```yaml
x-java-service: &java-service
  restart: unless-stopped
  stop_grace_period: 40s              # > spring graceful shutdown (25s)
  read_only: true
  tmpfs:
    - /tmp:uid=10001,gid=10001,mode=1777,size=128m
  security_opt:
    - no-new-privileges:true
  cap_drop: [ALL]
  networks: [backend]
```

Secrets are required rather than defaulted: `OMS_JWT_SECRET: ${OMS_JWT_SECRET:?set OMS_JWT_SECRET in .env (openssl rand -base64 48)}` makes `docker compose` refuse to start when the variable is unset. Ordering uses `depends_on` with conditions (`service_healthy` for PostgreSQL and Redis, `service_completed_successfully` for the one-shot `kafka-init` container that creates topics), because plain `depends_on` only waits for container start, not readiness. Host ports bind to `127.0.0.1` only, and PostgreSQL, Kafka and Redis publish no host port. All of this is unexecuted; the header says so.

In Kubernetes the same ideas become a Deployment, Service, PodDisruptionBudget and HPA in one file per service (`infrastructure/kubernetes/base/order-service.yaml`). The probe section:

```yaml
startupProbe:                      # allows slow JVM + Flyway on first boot without liveness kills
  httpGet:
    path: /actuator/health/liveness
    port: http
  periodSeconds: 5
  failureThreshold: 36             # 3 minutes
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: http
  periodSeconds: 10
  timeoutSeconds: 3
  failureThreshold: 3
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: http
  periodSeconds: 20
  timeoutSeconds: 3
  failureThreshold: 3
```

The three probes answer three questions. **Startup** gates the other two until the app has started (JVM warm-up plus Flyway migrations on first boot can take a minute; without it the liveness probe could kill a slow starter in a restart loop). **Readiness** decides whether the pod is in the Service's endpoints; when it fails the pod gets no traffic but is not restarted. **Liveness** decides whether to restart the container, so it must be cheap and must not depend on downstream systems: Spring Boot's liveness group reflects only the application's own state, while the readiness group can include dependencies, which is why a database outage makes pods unready (traffic stops) rather than restart-looping. The shared contract (`docs/architecture/00-shared-contract.md` section 5.5) names `/actuator/health/{liveness,readiness}` as the probe endpoints, and `management.endpoint.health.probes.enabled=true` is what exposes the groups (visible in the gateway's `application.yml`; `infrastructure/README.md` lists it as an assumption for the other services).

The same manifest handles the resource model and the rollout:

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1            # one extra pod during rollout
    maxUnavailable: 0      # never drop below desired capacity (zero-downtime)
# ...
resources:
  requests:
    cpu: 250m
    memory: 512Mi
  limits:
    cpu: "1"
    memory: 1Gi
```

Requests are what the scheduler reserves and what the HPA's CPU percentage is measured against; limits are the cgroup ceiling. Exceeding the memory limit kills the container (OOMKilled); exceeding the CPU limit throttles it. Setting request below limit gives the Burstable QoS class; setting both equal gives Guaranteed. With `maxSurge: 1` and `maxUnavailable: 0`, the controller starts one new pod, waits for it to become ready, then removes one old pod, so capacity never dips. Shutdown is coordinated: `terminationGracePeriodSeconds: 45` exceeds a `preStop` `sleep 10` (lets the endpoints controller and ingress stop routing to the terminating pod) plus the 25 s Spring drain.

### Example 2: configuration, secrets, and Kustomize overlays

Non-secret settings are a ConfigMap injected as environment variables (`infrastructure/kubernetes/base/configmap.yaml`), and the base default is deliberately safe:

```yaml
# SECURE DEFAULT: no environment profile here. Every overlay MUST set its own (dev/test: "dev,json-logs"; prod: "prod,json-logs").
# "dev" enables the token endpoint, seed data and swagger, so it must never be the base default.
SPRING_PROFILES_ACTIVE: "json-logs"
OMS_DB_HOST: "postgres"
KAFKA_BOOTSTRAP_SERVERS: "kafka:9092"
```

Secrets are referenced, never stored. The Deployment reads `OMS_JWT_SECRET` and the database credentials with `secretKeyRef` to a Secret named `oms-secrets`, and no overlay renders a Secret object (`scripts/validate-infra.sh` checks for literal `*PASSWORD`/`*SECRET` env values). Dev secrets come from `infrastructure/kubernetes/examples/create-dev-secret.sh`, which streams random values to `kubectl`; production uses the External Secrets Operator example (`examples/externalsecret.example.yaml`) reading AWS Secrets Manager. Remember what a Kubernetes Secret is: base64-encoded, not encrypted, unless etcd encryption at rest is enabled (the Terraform enables KMS envelope encryption for EKS secrets) and RBAC restricts `get secrets`.

Overlays customise one base. `infrastructure/kubernetes/overlays/prod/kustomization.yaml` (excerpt):

```yaml
namespace: oms-prod
resources:
  - ../../base
replicas:
  - { name: order-service, count: 3 }
  - { name: inventory-service, count: 3 }
patches:
  - target: { kind: HorizontalPodAutoscaler }
    patch: |-
      - { op: replace, path: /spec/minReplicas, value: 3 }
      - { op: replace, path: /spec/maxReplicas, value: 12 }
  - target: { kind: ConfigMap, name: oms-config }
    patch: |-
      - { op: replace, path: /data/SPRING_PROFILES_ACTIVE, value: "prod,json-logs" }
      - { op: replace, path: /data/MANAGEMENT_TRACING_SAMPLING_PROBABILITY, value: "0.05" }
```

The dev overlay (`overlays/dev/kustomization.yaml`) adds `../../dev-dependencies` (single-replica in-cluster PostgreSQL, Kafka and Redis StatefulSets), sets one replica per service, deletes the PodDisruptionBudgets (a PDB with a single replica would block node drains) and sets the profile to `dev,json-logs`. The prod overlay uses managed endpoints that are literal placeholders (`oms-pg.cluster-PLACEHOLDER.eu-west-1.rds.amazonaws.com`) and its header says images "should be referenced by DIGEST in prod". Kustomize patches are either strategic-merge or JSON 6902 (the `op: replace` form above); `images:` rewrites image names and tags per environment, which is how CI promotes a build.

## Advanced tier

### Example 1: autoscaling, disruption budgets and spreading

The base HPA scales on CPU, with a slow scale-down to avoid flapping (`infrastructure/kubernetes/base/order-service.yaml`):

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  minReplicas: 2
  maxReplicas: 6
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
```

`Utilization` is a percentage of the CPU *request* (250m here), so changing the request changes when scaling triggers. CPU is a poor signal for a Kafka consumer: a service can be far behind and still idle on CPU if each message waits on the database. `infrastructure/kubernetes/keda/scaledobject-kafka-lag.yaml` is an example (not wired into any overlay) that scales inventory-service on consumer lag:

```yaml
triggers:
  - type: kafka
    metadata:
      bootstrapServers: kafka:9092
      consumerGroup: inventory-service       # consumer group id = service name (contract section 7)
      topic: oms.inventory.commands.v1
      lagThreshold: "50"
      activationLagThreshold: "5"
```

Its comments carry the two traps: KEDA creates its own HPA, so the plain HPA from base must be removed for the same Deployment, and `maxReplicaCount` must not exceed the topic partition count (3), because consumers beyond the partition count would sit idle. That is a good place to connect the layers: the stack's idempotent consumers (`processed_events`) make it safe to add or remove consumers and to redeliver messages during a rollout.

PodDisruptionBudgets (`minAvailable: 1` in base, 2 for order, inventory and gateway in prod) limit voluntary evictions such as node drains and cluster upgrades, so a drain cannot take all replicas at once. They do not protect against involuntary failures such as a node crash. Spreading is configured with `topologySpreadConstraints`: base spreads across hostnames with `ScheduleAnyway`; the prod overlay changes that to `DoNotSchedule` and adds a second constraint on `topology.kubernetes.io/zone`, so replicas survive the loss of an availability zone. The honest limit: the HPA, PDB and spread behaviours were never exercised, and the K8s README says so ("Probes, NetworkPolicies, PDBs, HPA behaviour and the stateful dev dependencies are unverified at runtime").

### Example 2: zero-trust NetworkPolicy, pod security, rollback, and a latent mismatch to verify

`infrastructure/kubernetes/base/networkpolicies.yaml` starts from default deny in both directions and then opens only what the architecture needs:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-all
spec:
  podSelector: {}
  policyTypes: [Ingress, Egress]
```

Then an explicit allow for the gateway and for order-service calling inventory:

```yaml
# gateway -> backend services (ingress side on the services; order-service may also call inventory-service)
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-gateway-and-order-to-services
spec:
  podSelector:
    matchLabels: { app.kubernetes.io/component: service }
  policyTypes: [Ingress]
  ingress:
    - from:
        - podSelector:
            matchLabels: { app.kubernetes.io/name: api-gateway }
      ports:
        - { protocol: TCP, port: 18081 }
        # ... 18082, 18083, 18084 ...
    - from:
        - podSelector:
            matchLabels: { app.kubernetes.io/name: order-service }
      ports:
        - { protocol: TCP, port: 18082 }     # order -> inventory REST validation
```

Policies are additive allow-lists: once `podSelector: {}` selects every pod with both policy types and no rules, nothing is allowed, including DNS, which is why `allow-dns-egress` exists. NetworkPolicy only works if the CNI enforces it (Calico, Cilium, or the AWS VPC CNI with network policy enabled); on a CNI that does not, the objects are silently ignored. The policy file's comments flag a placeholder: the `ipBlock` CIDR `10.0.0.0/8` for managed data services must be tightened to the data subnets. The namespace is labelled for Pod Security Admission `restricted` (`base/namespace.yaml`), and the pod specs satisfy it with `runAsNonRoot`, `seccompProfile: RuntimeDefault`, `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true` and `capabilities: drop: ["ALL"]`; `automountServiceAccountToken: false` removes the default API token from pods that never call the Kubernetes API. The ingress (`base/ingress.yaml`) deliberately routes only `/api` (to the gateway) and `/` (to the UI); `/actuator/**` is never exposed.

Rollout and rollback are in `infrastructure/kubernetes/README.md`: `kubectl rollout status deploy/order-service --timeout=300s`, then `kubectl rollout undo deploy/order-service` to return to the previous ReplicaSet (`revisionHistoryLimit: 5`), with the preferred GitOps form being to revert the image-tag commit rather than edit live objects. The caveat to repeat: a rollback restores code, not data. Database changes must be backward compatible (expand/contract with Flyway), event schemas are additive-only so an old pod can read new events, and business effects of orders processed by the new version stay.

A finding worth knowing, because it shows how static validation can miss runtime defects. The image originally set `-Djava.io.tmpdir=/tmp/oms` and created that directory, while the Kubernetes Deployments mount an `emptyDir` at `/tmp`, which hides any directory baked into the image under `/tmp`. The fix (applied) was to align everything on `/tmp`: the JVM uses `-Djava.io.tmpdir=/tmp`, Compose mounts a `tmpfs` at `/tmp`, and Kubernetes mounts an `emptyDir` at `/tmp`. Still not verified at runtime, because nothing was run in Docker or on a cluster.

## How this repository implements it

| Concept | Where in repo | Notes |
|---|---|---|
| Layered multi-stage Java images | `infrastructure/docker/{order,inventory,fulfillment,notification}-service.Dockerfile`, `api-gateway.Dockerfile` | identical shape; JRE-only runtime, uid 10001, `HEALTHCHECK`, graceful stop |
| Build-context hygiene | `.dockerignore`, `infrastructure/docker/.dockerignore`, `frontend/oms-ui/.dockerignore` | whitelist of jars only for Java images |
| Frontend image | `frontend/oms-ui/Dockerfile`, `frontend/oms-ui/nginx.conf` | Node build stage, `nginx-unprivileged`, runtime `config.json` via envsubst, CSP headers |
| Local stack | `infrastructure/compose/docker-compose.yml`, `docker-compose.observability.yml`, `infrastructure/compose/kafka/create-topics.sh` | secrets required via `${VAR:?}`, health-gated `depends_on`, localhost-only ports |
| Kubernetes base | `infrastructure/kubernetes/base/*.yaml` | 5 Java Deployments plus Services, PDBs, HPAs, frontend, ingress, NetworkPolicies |
| Overlays | `infrastructure/kubernetes/overlays/{dev,test,prod}/kustomization.yaml` | namespace, images, replicas, profile and endpoints patches |
| Dev dependencies | `infrastructure/kubernetes/dev-dependencies/` | single-replica Postgres, Kafka (KRaft), Redis; dev and test only |
| Lag-based scaling | `infrastructure/kubernetes/keda/scaledobject-kafka-lag.yaml` | example only, not in any overlay |
| Secrets | `infrastructure/kubernetes/examples/` | ExternalSecret example, shape-only Secret example (never apply), dev script |
| Monitoring add-on | `infrastructure/kubernetes/monitoring/` | ServiceMonitor and PrometheusRule; needs Prometheus Operator CRDs |
| Static validation | `scripts/validate-infra.sh`, `.github/workflows/ci.yml` (`infra-validate`, `docker`, `docker-ui` jobs) | PASS/FAIL/SKIP reporting; CI never executed |

### Demonstration-only vs production-grade

Production-grade design present: layered images with a pinned base tag, non-root numeric uid, read-only root filesystem, dropped capabilities, probes that distinguish startup, readiness and liveness, graceful shutdown chain, zero-downtime rolling update, PDBs, zone spread in prod, default-deny network policy, Pod Security `restricted`, secrets by reference only, safe base profile, image scanning with Trivy in the CI definition, and a documented rollback with expand/contract.

Not proven or not production-ready: **no image was built and no container was run** (Docker unavailable); Compose never executed; Kubernetes manifests were rendered and schema-checked but never applied, so probes, NetworkPolicies, PDBs, HPA and the `/tmp` mount are unverified at runtime; base images are tag-pinned but not digest-pinned and GitHub Actions are pinned by major tag, not SHA; Kafka and the dev data stores are plaintext and single-replica; the NetworkPolicy managed-service CIDR is a placeholder; the registry path `ghcr.io/example-org/...` and every host name are placeholders; the K8s manifests assume property names (`OMS_GATEWAY_ROUTES_*`, `OMS_INVENTORY_BASE_URL`) documented as "guesses" in `infrastructure/README.md`; the gateway names were later confirmed in contract section 13, while `OMS_INVENTORY_BASE_URL` was not re-checked for this chapter, and none of it ran in a cluster; KEDA needs the operator installed and is unwired; Promtail mounts the Docker socket in the observability Compose file (laptop only, as the README says).

## Common mistakes and gotchas

1. **Running as root, or `USER` by name.** Use a numeric uid so `runAsNonRoot` can be verified.
2. **`latest` or unpinned tags.** Mutable tags break reproducibility; pin by digest in production.
3. **Copying source before dependencies.** It invalidates the dependency layer on every code change. Split layers, as the Spring Boot `extract --layers` does.
4. **Shell-form `ENTRYPOINT` without `exec`.** The JVM is not PID 1 and never sees SIGTERM; shutdown becomes a 30 s kill.
5. **Setting `-Xmx` equal to the container memory limit.** Non-heap memory pushes the process over the limit; use `MaxRAMPercentage`.
6. **Liveness probe that checks dependencies.** A database blip then restarts every pod. Liveness checks only the app; readiness may check dependencies.
7. **No startup probe on a slow JVM.** Liveness kills the pod during Flyway migration or warm-up.
8. **Confusing CPU requests with limits.** HPA percentages use requests; a tiny request makes the HPA scale too early, a huge one too late.
9. **KEDA and a CPU HPA on the same Deployment.** They fight; remove one. And never set `maxReplicaCount` above the partition count.
10. **Assuming Secrets are encrypted.** They are base64 unless etcd encryption is on; restrict RBAC and use an external secret store.
11. **NetworkPolicies on a CNI that does not enforce them.** They look correct and do nothing.
12. **Default deny without a DNS egress rule.** Everything breaks mysteriously.
13. **Mounting over a path that the image prepared.** A volume mounted at `/tmp` hides anything the image created under `/tmp` (this was a latent issue here, fixed by pointing the JVM tmpdir at `/tmp` itself).
14. **Treating `kubeconform` as proof of behaviour.** It checks schema, not whether a probe path exists or a policy blocks needed traffic.
15. **Rolling back code without thinking about the schema.** Keep migrations backward compatible.

## Quick-recall cheat sheet

* Image = ordered layers; stable layers first; multi-stage drops build tools; this repo builds jars outside Docker and copies them in, with Spring Boot `-Djarmode=tools extract --layers`.
* Hardening here: JRE alpine, uid 10001, `read_only`/`readOnlyRootFilesystem`, `cap_drop: ALL`, `no-new-privileges`, whitelist `.dockerignore`.
* JVM in containers: `UseContainerSupport`, `MaxRAMPercentage=75`, `ExitOnOutOfMemoryError`, `exec java` for PID 1, graceful shutdown 25 s.
* Compose: `depends_on` with `service_healthy` / `service_completed_successfully`, `${VAR:?}` for required secrets, ports on 127.0.0.1.
* Kubernetes objects here: Deployment (RollingUpdate `maxSurge 1`, `maxUnavailable 0`), Service (ClusterIP), PDB, HPA (CPU 70, scale-down window 300 s), Ingress (`/api` and `/` only), NetworkPolicies (default deny), Namespace with PSA restricted.
* Probes: startup (36 x 5 s), readiness (traffic), liveness (restart); `preStop sleep 10` plus `terminationGracePeriodSeconds 45`.
* Config: ConfigMap via `envFrom`, Secret via `secretKeyRef` only; base profile is `json-logs`, overlays set `dev,json-logs` or `prod,json-logs`.
* Kustomize: base plus overlays, `replicas`, `images`, JSON 6902 patches, `$patch: delete`.
* Scaling: HPA on CPU for stateless APIs; KEDA on Kafka lag for consumers, capped at 3 partitions.
* Rollback: `kubectl rollout undo`, or revert the image-tag commit; schema must be backward compatible.
* Honest status: images not built, Compose not run, manifests rendered and `kubeconform -strict` checked only, nothing applied; the `/tmp` tmpdir mismatch was fixed statically only.

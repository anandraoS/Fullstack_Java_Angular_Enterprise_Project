# Microservices and Distributed Systems: Boundaries, Consistency, Resilience and Operations

The platform is four services plus an API gateway: `order-service`, `inventory-service`, `fulfillment-service`, `notification-service` and `api-gateway`, with a shared technical library `platform-common`. ADR-0001 is unusually honest about it: a team this size would normally start with a modular monolith, and services were chosen so the distributed failure modes are real and demonstrable. That honesty is itself an excellent interview answer, and this chapter is organised around defending (and criticising) those choices.

## Big picture and kid-friendly analogy

Imagine a school cafeteria with four stations: pizza, salad, drinks and the cashier who hands out the pick-up numbers.

* In a **monolith** there is one giant kitchen and one giant notebook. It is simple, but if someone spills something everything stops, and everybody must change the notebook format together.
* In **microservices** each station has its own staff and its **own cash box and stock notebook** (database per service). Pizza does not peek into the salad notebook. If it needs to know something it asks, or it listens for a note someone pins to the board.
* Passing **notes on a board** (events) is slower than shouting but nobody has to wait: if the drinks station is on break, the note is still there when they return. **Shouting across the room** (a synchronous call) is fast but only works if the other person is listening *right now*.
* The **head lunch lady** (the orchestrator) keeps a clipboard for each student's tray: which station is done, which is still working.
* If the phone line between two stations is cut (a **network partition**), each station has to choose: keep selling from its own notebook and risk selling the last slice of pizza twice (availability), or stop selling until the line is back (consistency). That is the CAP theorem.
* A **fuse box** stops one broken toaster from burning down the whole school (circuit breaker, timeout, bulkhead).

| Analogy | Concept |
|---|---|
| Station with its own cash box | Service owning its data (database per service, ADR-0004) |
| Note pinned on a board | Asynchronous event on Kafka |
| Shouting across the room | Synchronous REST call with timeouts |
| Head lunch lady's clipboard | Saga orchestrator state (`saga_instances`) |
| Phone line cut | Network partition, CAP trade-off |
| Fuse box | Circuit breaker, timeouts, bulkheads |
| Front door with a guard | API gateway |

## Beginner tier

### Example 1: service boundaries follow data ownership

Boundaries come from who owns which data (`docs/architecture/00-shared-contract.md` section 3):

| Service | Owns | Notes |
|---|---|---|
| order-service | customers, orders, items, history, saga instances, idempotency keys | Orchestrates the order saga |
| inventory-service | products, stock, reservations, stock movements | Hot reads and writes, scales on its own |
| fulfillment-service | fulfillments and events | Simulated picking, packing, shipping |
| notification-service | notifications and attempts | Consumes events only; tolerates delay |
| api-gateway | nothing persistent | Edge: JWT, CORS, rate limit, routing |

The runtime enforces the ownership. Locally each service uses its own in-memory H2 database (order-service default in `services/order-service/src/main/resources/application.yml`: `jdbc:h2:mem:order_db;MODE=PostgreSQL;...`), and in Compose or Kubernetes there is one PostgreSQL server with a database **and a login role per service**, where each role can only connect to its own database (`infrastructure/compose/postgres/init/01-create-databases.sh`):

```sh
create_db oms_order        order_db        "${ORDER_DB_PASSWORD:-}"
create_db oms_inventory    inventory_db    "${INVENTORY_DB_PASSWORD:-}"
create_db oms_fulfillment  fulfillment_db  "${FULFILLMENT_DB_PASSWORD:-}"
create_db oms_notification notification_db "${NOTIFICATION_DB_PASSWORD:-}"
```

Data one service needs from another arrives in one of three ways (ADR-0004): event payloads that carry what the consumer needs (`OrderCreated` includes `customerEmail`, so notification never calls order); the single synchronous validate call; or a local denormalised snapshot (`order_items` keep `sku`, `name` and `unitPrice` at order time). Never a cross-database join. The trade-off to name: there are no cross-service foreign keys, so referential integrity across services is eventual and checked by reconciliation (chapter 06). A subtle design point: notification-service keeps its own `order_contacts(order_id, customer_id, customer_email)` table fed from every order event, because fulfillment events carry only an `orderId`.

### Example 2: synchronous versus asynchronous communication, and why only one hop is synchronous

The rule in ADR-0001 is "no synchronous call chains deeper than one hop". The only synchronous call is order-service to inventory-service for product validation; everything else is a command or event over Kafka. The synchronous client (`services/order-service/src/main/java/com/enterprise/oms/order/saga/RestInventoryClient.java`) builds on a shared factory that applies the outbound timeouts and forwards the correlation id (`services/platform-common/src/main/java/com/enterprise/oms/common/http/OmsRestClientFactory.java`):

```java
SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
rf.setConnectTimeout((int) props.getConnectTimeout().toMillis());
rf.setReadTimeout((int) props.getReadTimeout().toMillis());
RestClient.Builder b = RestClient.builder().baseUrl(baseUrl).requestFactory(rf)
        .requestInterceptor((request, body, execution) -> {
            String id = CorrelationIds.currentOrCreate();
            request.getHeaders().set(CorrelationIds.HEADER, id);
```

Per the contract the defaults are connect 2 s and read 3 s. Why a call at all, when events could carry everything? Because the order needs a **price snapshot** and an early rejection of unknown or inactive products before it commits stock. Notice how the answer is treated: the validate endpoint is documented as "advisory; reservation is authoritative" (`ValidationService` Javadoc). Stock can change between validation and reservation, so the reservation step re-checks atomically (chapter 06). Treating a read as advisory and a write as authoritative is a classic pattern for cross-service reads.

Choosing sync versus async, as a rule of thumb: use sync when the caller needs the answer to proceed and can tolerate the callee being down for a short time (with timeouts and a breaker); use async when the work can complete later, when you need to survive the callee being down, or when many consumers care about the same fact.

### Example 3: the API gateway as the single, hardened entry point

External clients only ever talk to the gateway (`gateway/src/main/java/com/enterprise/oms/gateway/config/RoutesConfig.java`). It does routing, JWT validation, CORS, rate limiting, size limits, correlation ids and circuit breaking, and it hides internal endpoints entirely: everything under `/api/v1/internal/**` answers 404 at the edge (before authentication), is not routed, and is further restricted by the SERVICE role in the service itself (`InternalController` has `@PreAuthorize("hasRole('SERVICE')")`). Network policy completes the picture (`infrastructure/kubernetes/base/networkpolicies.yaml`): default deny for ingress and egress, then explicit allows, so only the gateway (and, for validation, order-service) can reach the service ports:

```yaml
- from:
    - podSelector:
        matchLabels: { app.kubernetes.io/name: order-service }
  ports:
    - { protocol: TCP, port: 18082 }     # order -> inventory REST validation
```

Gateway trade-offs to mention: it is a single entry point (scale it horizontally and keep it stateless; breaker and rate-limit state are per replica unless Redis is enabled), it can become a "smart pipe" that accumulates business logic (here edge role rules are explicitly defence in depth and the services stay authoritative), and it adds a hop of latency.

## Intermediate tier

### Example 1: timeouts, retries, circuit breakers and bulkheads as one budget

Resilience is a set of numbers that must agree. Collect them in one table, then reason about them:

| Layer | Setting | Value | Source |
|---|---|---|---|
| order to inventory | connect / read timeout | 2 s / 3 s | contract, `OmsRestClientFactory` |
| order to inventory | retry | 3 attempts, 200 ms doubling | `services/order-service/src/main/resources/application.yml` |
| order to inventory | breaker | 10-call window, 50 percent, 10 s open | same file |
| gateway to service | connect / response timeout | 2 s / 10 s | `gateway/src/main/resources/application.yml` |
| gateway breaker | window / min calls / open | 20 / 10 / 10 s | same file |
| gateway breaker time limiter | duration | 12 s (must exceed the 10 s response timeout) | `gateway/README.md` |
| saga | considered stuck after | 60 s (30 s in `local`) | `SagaProperties` |
| inventory reservation | TTL | 900 s | contract section 7 |

Simple arithmetic (our calculation) shows why the layers are separated: one validation call can take up to about 3 s x 3 attempts plus backoff, nearly 10 s in the worst case. That is fine because validation runs on the saga executor thread **after** the `202` was returned, not on the HTTP request thread, and it holds no database connection (the REST call is made outside any transaction, `OrderSagaOrchestrator.validate`). If the validation call were inside `POST /orders` the gateway's 10 s response timeout would be at risk.

Bulkheads isolate resource pools so one slow dependency cannot consume everything. This repository uses plain resource limits rather than the Resilience4j Bulkhead annotation: a dedicated saga executor (core 2, max 4, queue 200, `AbortPolicy`, `OrderServiceConfig`), a bounded Hikari pool (10 locally, 20 in `prod`, with a 3 s connection timeout so a DB outage fails fast instead of stacking threads), a bounded in-memory rate-limit map, and a per-upstream circuit breaker at the gateway (`order`, `inventory`, `fulfillment`, `notification` each have their own named breaker, so a dead notification service does not trip the order route). Interview line: "a circuit breaker protects me from them; a bulkhead protects the rest of my service from this dependency; a timeout bounds how long either takes to decide".

Idempotency is the partner of retries. Anything retried (the saga re-sending a command, Kafka redelivery, a client retry after a gateway 503) must be safe to repeat. That is the entire reason for the idempotency key, `processed_events` and the state-machine no-ops.

### Example 2: service discovery alternatives and what this project chose

Options an interviewer expects you to compare:

| Approach | How it works | Used here |
|---|---|---|
| Client-side discovery (Eureka + client LB) | Services register; callers look up instances | No |
| Server-side discovery (Kubernetes Service + DNS) | Platform load-balances across ready pods | Yes |
| Static configuration | URL in config or environment | Yes (local and K8s) |
| Service mesh (Istio, Linkerd) | Sidecar proxies, mTLS, retries, routing | No (a possible upgrade) |
| DNS or cloud load balancer per service | Name resolves to an LB | Possible for managed setups |

Concretely, the gateway property `spring.cloud.loadbalancer.enabled: false` turns off client-side balancing; locally the URIs are `http://localhost:18081..18084`, and in Kubernetes the ConfigMap supplies `http://order-service:18081`, `http://inventory-service:18082` and so on (`infrastructure/kubernetes/base/configmap.yaml`). A rolling deployment (`maxUnavailable: 0`, readiness probes, a 10 s `preStop` sleep so endpoints drop the pod before SIGTERM) provides the equivalent of instance registration and deregistration. Say plainly what is lost: no runtime visibility of instances in a registry, no weighted or zone-aware client routing, and no config push without a rollout. A service mesh is what you would reach for if you needed mTLS between services, uniform retries and traffic shifting without code.

### Example 3: independent deployability is a property of contracts, not of repositories

Independent deployment only works if changes are backward compatible both ways. The repository encodes that discipline:

* REST: additive-only changes inside `/api/v1`; clients ignore unknown fields (ADR-0007, `docs/architecture/api-versioning.md`).
* Events: `schemaVersion` plus a **tolerant reader** (`FAIL_ON_UNKNOWN_PROPERTIES=false`), unknown event types ignored and counted, topic suffix `.v1` bumped only for breaking changes with dual publishing during migration (`docs/architecture/event-schema-evolution.md`, `EventJson`).
* Database: Flyway per service, shared platform migrations reserved to `V1..V8`, service migrations from `V10`, seed data from `V1000`; migrations must be backward compatible with the previous app version for a rolling update (expand, then contract).
* Runtime: Kubernetes `RollingUpdate` with `maxSurge: 1`, `maxUnavailable: 0`, a PodDisruptionBudget (`minAvailable: 1`), an HPA on CPU, and an example KEDA `ScaledObject` scaling consumers on Kafka lag (`infrastructure/kubernetes/keda/scaledobject-kafka-lag.yaml`, capped at the 3 partitions because extra consumers would be idle).

A warning sign worth knowing: the shared library `platform-common` is versioned together with the reactor (`1.0.0`). If every release of any service forces a coordinated release of all others, you have a distributed monolith. ADR-0001 lists the merge-back criteria (single team, lock-step deploys in more than half of releases, no independent scaling).

### Example 4: observability for a flow that spans five processes

A single order touches the gateway, order, inventory, fulfillment and notification. The tools that make that debuggable are in the code, not only in dashboards:

* A **correlation id** is accepted or generated at the edge (`EdgeFilters.CorrelationIdWebFilter`, `CorrelationIdFilter` in services), put in the MDC, forwarded in `X-Correlation-Id` on REST calls (`OmsRestClientFactory`), embedded in every event envelope and Kafka header (`correlationId`), restored into the MDC on the consumer side (`KafkaConsumerConfigSupport.CorrelationRecordInterceptor`) and returned in every problem+json body.
* **Tracing**: Micrometer Tracing with the OpenTelemetry bridge; off by default locally (`management.tracing.enabled=${OMS_TRACING_ENABLED:false}`), sampling `0.1`, OTLP endpoint configurable; Kafka observation is enabled on templates and listeners (`spring.kafka.template.observation-enabled`).
* **Metrics**: `oms.events.processed|duplicates|retries|dead_lettered`, `oms.outbox.pending`, `oms.saga.active`, `oms.saga.compensations`, `oms.order.processing.duration` and the standard `http.server.requests` histograms, scraped from `/actuator/prometheus`.
* **Operational APIs**: `GET /api/v1/ops/messaging` (lag, DLT names, outbox pending), `/api/v1/ops/audit`, and the gateway's aggregate `/api/v1/ops/health`.

Rule from ADR-0011: never label metrics by an unbounded value such as `orderId`; use logs and traces for per-order detail.

## Advanced tier

### Example 1: CAP, PACELC and where consistency actually lives

CAP says that during a partition you must choose between consistency and availability; PACELC adds that even without a partition you trade latency for consistency. Do not answer in the abstract; show where each choice was made here:

* **Inside a service: strong consistency** with local ACID transactions. The "never oversell" invariant is a single SQL predicate (`UPDATE stock SET reserved = reserved + :q ... WHERE on_hand - reserved >= :q`, ADR-0006), executed under a row lock in one transaction with the reservation row, items, movements and the outbox event. Nothing eventually consistent can oversell.
* **Across services: eventual consistency** with visible intermediate states (`CREATED`, `VALIDATED`, `INVENTORY_RESERVED`, `COMPENSATION_PENDING`). It chooses availability: orders are accepted while inventory is down (`202`, then recovery).
* **Bounded staleness by design**: reservation TTL (900 s) prevents a lost reply from locking stock forever; the recovery job re-drives silent steps after 60 s; reconciliation repairs what remains.
* **Distributed ordering races are handled explicitly**. The `ReleaseInventory` command can arrive before (or without) the reservation it cancels. Inventory writes a `RELEASED` **tombstone** for that `orderId`, so a late or duplicate `ReserveInventory` can never take stock (contract section 14). That is the kind of detail that shows you have thought about "what if messages reorder".

Also be ready for the no-distributed-transaction argument: two-phase commit blocks participants on a coordinator failure, needs XA support from every resource (Kafka has none for PostgreSQL), and hurts availability. Sagas trade isolation for availability. The cost is that intermediate states are visible and compensations are semantic, not rollbacks.

### Example 2: a failure walk-through you can narrate

"Inventory is down when a customer places an order." Following the code:

1. `POST /orders` writes the order, saga row and idempotency key in one transaction and returns `202` (no dependency on inventory).
2. After commit, `SagaTrigger` hands validation to the saga executor. The REST call fails; `RestInventoryClient` throws `InventoryUnavailableException`; Resilience4j retries 3 times; the breaker records failures.
3. `OrderSagaOrchestrator.onValidationUnavailable` increments the saga attempt counter and logs a `FAILED` step; the order stays `CREATED`.
4. Once the saga has been idle for `oms.saga.stuck-after-seconds`, `SagaRecoveryJob` calls `recover`, which validates again. With the circuit open, calls fail fast (no thread burned on a 3 s timeout).
5. When inventory returns, validation succeeds and the saga proceeds. If `oms.saga.max-attempts` (default 5) is exhausted, `exhaust` moves a `CREATED` order to `FAILED` with an audited reason.

The README's failure-mode table (`services/order-service/README.md` section 12) covers 12 such scenarios (Kafka down, relay crash, silent fulfillment, poison message, Redis down, database down). Practise telling two of them end to end, including which metric moves.

### Example 3: sharing code between services without building a distributed monolith

`platform-common` is the most debatable decision. ADR-0001 sets the rule: **share technical code (envelope, outbox, idempotency, security, error model), never business code or entities**. What it buys: the same delivery guarantees and error contract in every service, plus the `/api/v1/ops/messaging` and `/api/v1/ops/audit` endpoints for free. What it costs: a lock-step upgrade path (a breaking change in the library is a coordinated release), transitive coupling to Spring Boot and Kafka versions, and auto-configuration surprises (the reactive gateway has to exclude eight auto-configurations, see chapter 02). Alternatives: copy-paste (diverges), a sidecar or service mesh for cross-cutting concerns (language neutral, more infrastructure), or a smaller library limited to wire formats. Be able to argue both sides.

Likewise the **orchestrator as a coordination hot spot** (ADR-0003): order-service knows the whole flow, so it must be highly available and crash-safe. Mitigations are in the code: all state and outbox rows in one transaction, `@Version` on the saga row so concurrent handlers cannot both advance it, idempotent commands at every receiver, and recovery plus reconciliation as safety nets.

### Example 4: scheduled jobs and the relay on every replica

Several background components run on every instance: the outbox relay, `SagaRecoveryJob`, `ReconciliationService`, reservation expiry and idempotency-key cleanup. The code makes this safe rather than unique: commands are idempotent, the saga row is `@Version`ed, expiry uses guarded UPDATEs, and consumers deduplicate. The README is explicit that this duplicates work and that before running many replicas you should add row claiming for the relay (`FOR UPDATE SKIP LOCKED`) and a scheduler lock such as ShedLock or enable jobs on one instance (`services/order-service/README.md` section 13). In an interview this is a good example of "correct but not efficient yet" and of stating your scaling limits honestly.

## How this repository implements it

| Concept | Where in repo | Notes |
|---|---|---|
| Service boundaries and data ownership | `docs/architecture/00-shared-contract.md` section 3, `docs/adr/0001-microservices-vs-modular-monolith.md`, `docs/adr/0004-database-per-service.md` | One logical database per service |
| Database per service | `infrastructure/compose/postgres/init/01-create-databases.sh`, each `application.yml` | One PostgreSQL instance, separate roles |
| Single sync hop with resilience | `services/order-service/src/main/java/com/enterprise/oms/order/saga/RestInventoryClient.java`, `application.yml` | Retry plus breaker, 2 s/3 s timeouts |
| API gateway | `gateway/src/main/java/com/enterprise/oms/gateway/*` | Static routes, per-upstream breakers |
| Network isolation | `infrastructure/kubernetes/base/networkpolicies.yaml` | Default deny, explicit allows |
| Discovery by platform | `infrastructure/kubernetes/base/configmap.yaml`, gateway `loadbalancer.enabled: false` | No Eureka |
| Deployment independence | `infrastructure/kubernetes/base/*.yaml`, `infrastructure/kubernetes/keda/` | Rolling, PDB, HPA, KEDA example |
| Correlation and tracing | `services/platform-common/src/main/java/com/enterprise/oms/common/web/CorrelationIdFilter.java`, `OmsRestClientFactory.java`, `oms-defaults.properties` | Tracing off locally |
| Eventual consistency machinery | `services/order-service/src/main/java/com/enterprise/oms/order/saga/*`, `docs/architecture/saga-and-consistency.md` | See chapter 06 |
| Merge-back criteria | `docs/adr/0001-microservices-vs-modular-monolith.md` | Honest "start monolith" framing |

### Demonstration-only vs production-grade

* **Production-grade patterns**: ownership enforced by credentials, one synchronous hop, explicit timeouts, breakers per upstream, default-deny network policies, additive-only contracts, correlation ids end to end, PodDisruptionBudget and graceful shutdown, secrets from Kubernetes Secrets.
* **Demonstration-only**: no Eureka or Config Server (by design: static URIs, Kubernetes Services, ConfigMaps and Secrets), no service mesh or mTLS (service calls use a SERVICE JWT minted from a **shared HS256 secret**, so any service can impersonate another); one shared PostgreSQL instance is still a shared failure and noisy-neighbour domain; locally everything is one machine with an embedded single-broker Kafka (replication factor 1) and H2; breaker and rate-limit state are per replica; no multi-region story; the relay and jobs run on every replica without row claiming or scheduler locks.
* **Verification honesty**: Docker is not installed on the dev machine, so Testcontainers PostgreSQL and Kafka tests are skipped there; Compose, Kubernetes, Kustomize and Terraform files are statically validated only; real-broker failure injection and multi-instance behaviour were not exercised. The documented failure behaviours are covered by unit tests and embedded-KRaft Kafka tests (for example `OrderResilienceKafkaIT`), not by chaos testing.
* **The honest framing**: this is a reference system. For a real team of this size ADR-0001 itself recommends a modular monolith first.

## Common mistakes and gotchas

* Splitting by technical layer (a "database service") or by entity instead of by business capability and data ownership, producing chatty services.
* Shared database "just for reporting" that quietly becomes the integration point.
* Call chains: A calls B calls C synchronously; availability multiplies (99.9 percent x 99.9 percent x 99.9 percent is about 99.7 percent) and latency adds.
* Retrying without idempotency, or retrying at several layers at once (client, gateway, service) causing a retry storm.
* Timeouts that do not nest: a circuit-breaker time limiter shorter than the HTTP response timeout, or an upstream timeout larger than its caller's.
* Holding a database connection or a transaction open across a network call.
* Counting an HTTP 4xx as a breaker failure, so caller bugs open the circuit.
* Assuming eventual consistency means "no consistency": every invariant still needs an owner (local constraint, TTL, reconciliation).
* Distributed monolith: lock-step releases, shared entities, shared database, synchronous chains.
* Unbounded metric labels (`orderId`) and logging full payloads that contain emails.
* Treating the gateway as the only security layer; services must validate tokens and ownership too.
* Adopting Eureka or a Config Server on Kubernetes by habit.

## Quick-recall cheat sheet

* Boundaries follow data ownership and change rate; one logical database per service; no cross-service joins or foreign keys.
* Get data from other services by event payloads, one advisory sync call, or local snapshots.
* Sync only when the caller needs the answer now; keep chains at one hop; async for everything that can wait or fan out.
* Timeouts, retries (idempotent calls only), circuit breaker per dependency, bulkheads via bounded pools; make the numbers nest (connect 2 s, read 3 s, gateway 10 s, breaker limiter 12 s).
* Local ACID for invariants, sagas plus idempotency for cross-service workflows, TTLs and reconciliation for bounded inconsistency.
* CAP: during a partition choose C or A; PACELC adds latency versus consistency otherwise. Here: strong inside a service, eventual across services, availability preferred.
* No 2PC: coordinator blocking, missing XA support, availability cost.
* Discovery here = Kubernetes Service DNS + ConfigMap URIs; loses registry and client-side balancing, gains simplicity.
* Independent deploys need additive contracts, tolerant readers, expand-and-contract migrations, rolling updates with readiness probes.
* Observability trio: metrics for symptoms, logs for detail, traces for causality, tied by a correlation id.
* Shared library: technical code only; versions move together; beware lock-step releases.
* ADR-0001 merge-back triggers: single team, lock-step deploys over half of releases, no independent scaling, saga causing more incidents than it prevents.
* Honest scope: reference system; shared HS256 secret for service tokens, single PostgreSQL instance, replicas run relay and jobs without row claiming.

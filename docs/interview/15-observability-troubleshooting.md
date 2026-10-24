# Observability and Production Troubleshooting

An asynchronous order platform fails in ways no single log shows: a saga that never finishes, a consumer that quietly falls behind, a duplicate storm after a rebalance, a connection pool that fills because someone called a remote service inside a transaction. This chapter covers how this repository makes those failures visible (logs, metrics, traces, alerts, dashboards, ops endpoints) and how you investigate them with a method instead of a guess. The material comes from `observability/`, `services/platform-common`, `docs/runbooks/` (runbooks 01 to 08), `docs/mentoring/debugging.md` and ADR-0011. As with CI/CD, state the honesty note up front: the configs, dashboards and alert rules are authored and statically checked (`scripts/check-observability.py`, no `promtool`); **they have not been observed against a running Prometheus, Loki or Tempo**, and every runbook command is marked illustrative and unexecuted against a cluster.

## Big picture and kid-friendly analogy

Think of a hospital for your software.

* **Metrics** are the vital signs on the bedside monitor: heart rate, temperature, blood pressure. They are numbers over time, cheap to keep, great for noticing "something is off". They cannot tell you why.
* **Logs** are the nurse's notes: "10:42, patient complained of a headache, gave water." Detailed and specific, but you have to know which patient and which hour to look up.
* **Traces** are the patient's journey sheet: reception, X-ray, lab, ward, with the time spent at each stop. They show *where the time went* across departments.
* A **correlation id** is the wristband with a number. Every department writes the same number on its notes, so you can pull every note about one patient from every department in seconds.
* **Alerts** are the nurse who rushes in when the monitor beeps. A good alert fires on a *symptom the patient feels* (high fever for 5 minutes), not on every wobble of the line.
* The **runbook** is the laminated emergency card on the wall: "if the heart stops, do these steps in this order".
* A **blameless review** afterwards asks "what let this happen?" and never "whose fault was it?".

| Analogy | Technical concept |
|---|---|
| Vital signs monitor | Metrics (Micrometer, Prometheus, Grafana) |
| Nurse's notes | Structured logs (JSON, Loki) |
| Journey sheet | Distributed traces (OpenTelemetry, Tempo) |
| Wristband number | Correlation id and trace id |
| Monitor beep | Alert rule (`oms-alerts.yml`) |
| Emergency card | Runbook (`docs/runbooks/`) |
| Heart-rate, fever, breathing | RED (rate, errors, duration) and USE (utilisation, saturation, errors) |
| Review without blame | Post-incident review (`post-incident-review-template.md`) |

## Beginner tier

### Example 1: a correlation id on every log line, set by a filter and cleaned in a `finally`

The most valuable single piece of observability in the repository is tiny. `CorrelationIdFilter` (`services/platform-common/src/main/java/com/enterprise/oms/common/web/CorrelationIdFilter.java`) accepts `X-Correlation-Id` from the caller if it is valid (1 to 64 characters of letters, digits, `.`, `_`, `-`), otherwise generates a UUID, puts it in the SLF4J MDC, echoes it on the response and always removes it afterwards.

```java
String id = request.getHeader(CorrelationIds.HEADER);
if (!CorrelationIds.isValid(id)) {
    id = CorrelationIds.newId();
}
MDC.put(CorrelationIds.MDC_KEY, id);
response.setHeader(CorrelationIds.HEADER, id);
try {
    chain.doFilter(request, response);
} finally {
    MDC.remove(CorrelationIds.MDC_KEY);
}
```

Three details to explain. Validation matters: accepting arbitrary header text into logs is a log-injection vector, so anything that does not match the pattern is replaced (`CorrelationIds.VALID`). The `finally` matters: servlet threads are pooled, so a leftover MDC value would leak one request's id into the next request's logs (the same hazard is called out in the flawed-example list). And the filter runs first in the chain, so even security failures carry the id. For asynchronous hops the id crosses Kafka as a header named `correlationId`; on the consumer side `KafkaConsumerConfigSupport.CorrelationRecordInterceptor` copies the header into the MDC before the listener runs and removes it in `afterRecord`. The client UI also stamps `X-Correlation-Id` (see `frontend/oms-ui/src/app/core/http/interceptors.spec.ts`), and error toasts show it, so a user can quote the id in a support ticket.

### Example 2: structured JSON logs that a machine can query

In containers the `json-logs` Spring profile switches logging from a human console pattern to one JSON object per line. The shared `services/platform-common/src/main/resources/logback-spring.xml` uses `logstash-logback-encoder`:

```xml
<customFields>{"service":"${serviceName}"}</customFields>
<includeMdcKeyName>correlationId</includeMdcKeyName>
<includeMdcKeyName>traceId</includeMdcKeyName>
<includeMdcKeyName>spanId</includeMdcKeyName>
<throwableConverter class="net.logstash.logback.stacktrace.ShortenedThrowableConverter">
  <maxDepthPerThrowable>25</maxDepthPerThrowable>
  <rootCauseFirst>true</rootCauseFirst>
</throwableConverter>
```

Every line therefore has `timestamp, level, service, correlationId, traceId, spanId, logger, message` (contract section 10). `rootCauseFirst` puts the root exception at the top of a truncated stack trace, which is where an on-call engineer wants to look. Locally (no profile) the default console pattern still shows ids through `logging.pattern.correlation=[${spring.application.name:},%X{correlationId:-},%X{traceId:-}]` in `services/platform-common/src/main/resources/META-INF/oms-defaults.properties`. Logging rules: log identifiers, never payloads; never log tokens, passwords or full emails (`LogMasking.maskEmail` turns `alice@example.com` into `a***@example.com`; `LogMasking.sanitize` scrubs bearer tokens, raw JWTs and `password=` pairs; `LogMasking.stripNewlines` blocks log forging); INFO for state transitions, WARN for retries and ignored events, ERROR only when someone must act. Filtering locally is `jq 'select(.correlationId=="...")'`; in Loki it is `{service=~".+"} | json | correlationId="<id>"` (from `observability/README.md`).

## Intermediate tier

### Example 1: the metric catalogue, RED and USE, and why labels must be low-cardinality

Micrometer exposes metrics at `/actuator/prometheus`; Prometheus scrapes them every 15 seconds (`observability/prometheus/prometheus.yml`). The platform defines its own business and messaging metrics, with names fixed by the contract (`MessagingMetrics`, `Metrics`, `OrderMetrics`):

| Metric (Prometheus name) | Type | Meaning |
|---|---|---|
| `oms_events_processed_total{service,topic,type}` | counter | Events handled |
| `oms_events_duplicates_total` | counter | Redeliveries ignored by the idempotent processor |
| `oms_events_retries_total` | counter | Handler failures that were retried |
| `oms_events_dead_lettered_total` | counter | Records sent to a `.DLT` topic |
| `oms_outbox_pending` | gauge | Unpublished outbox rows |
| `oms_saga_active` | gauge | In-flight sagas |
| `oms_saga_compensations_total` | counter | Compensations started |
| `oms_order_processing_duration_seconds_*` | timer | Order CREATED to terminal duration |

Frameworks to name. **RED** for request-driven services: Rate (requests per second), Errors (5xx ratio), Duration (latency percentiles); the "OMS - API Rate / Errors / Duration (RED)" dashboard is exactly this. **USE** for resources: Utilisation, Saturation, Errors; the Hikari dashboard (active over max is utilisation, pending threads is saturation, timeouts are errors) and the JVM dashboard (heap used over max, GC pause fraction) follow it. Five dashboards are generated from one script (`observability/grafana/build-dashboards.mjs`) into `observability/grafana/dashboards/`: API RED, JVM, Hikari, Messaging, and Saga/Order funnel.

Cardinality is the hidden cost. A metric label with unbounded values (`orderId`, `customerId`) creates one time series per value and takes Prometheus down; the review checklist says "low-cardinality tags (never orderId/customerId as a tag)". The same reasoning is why Promtail turns only `level` into a Loki label and leaves `correlationId` and `traceId` inside the log line (`observability/loki/promtail.yml`). Another subtlety: percentile panels need histogram buckets. The README says "Without them, p95 panels are empty". A grep of the service configs finds `management.metrics.distribution.percentiles-histogram.http.server.requests: true` only in `services/order-service/src/main/resources/application.yml` and `services/inventory-service/src/main/resources/application.yml`. For fulfillment, notification and the gateway the p95 alert expression (`OmsHighP95Latency`) would silently return no data. That is a real gap worth finding in a review and a good example of why alerts need their own tests.

### Example 2: alerts that fire on symptoms, with `for:` windows

The 12 rules in `observability/prometheus/rules/oms-alerts.yml` are grouped by availability, API, messaging, saga and resources. Two examples show the style:

```yaml
      - alert: OmsHighErrorRate
        expr: |
          sum by (job) (rate(http_server_requests_seconds_count{job=~"oms-.*",status=~"5.."}[5m]))
            /
          sum by (job) (rate(http_server_requests_seconds_count{job=~"oms-.*"}[5m])) > 0.05
        for: 5m
        labels: { severity: critical }
...
      - alert: OmsHikariPoolExhaustion
        expr: |
          (hikaricp_connections_pending{job=~"oms-.*"} > 0)
          or
          (hikaricp_connections_active{job=~"oms-.*"} / hikaricp_connections_max{job=~"oms-.*"} > 0.9)
        for: 3m
```

Principles. Alert on a *ratio* of errors, not a count, so a traffic spike does not page you. Use `for:` so a 20-second blip does not wake anyone. Pair a cause alert (`OmsHikariPoolExhaustion`) with a symptom alert (`OmsHighErrorRate`), and make severity mean something (`critical` pages, `warning` creates a ticket). The messaging group alerts on lag over 1000 records for 5 minutes, any dead-letter increase in 15 minutes (`for: 0m`, because a DLT record means a message needs a human), outbox backlog above 100 rows and a retry storm. `OmsSagasStuck` is honestly documented as "a heuristic on `oms_saga_active`" because a per-saga stuck metric does not exist. The thresholds are explicitly "starting points, not tuned on measurements". The Kubernetes form, `infrastructure/kubernetes/monitoring/prometheusrule.yaml`, is generated from the same file by `scripts/gen-prometheusrule.py` ("Do not edit by hand"), so there is one source of truth.

**SLOs.** The repository has alerts but not formal SLOs; the post-incident template says "Error budget consumed (if SLOs defined: pending)". Know the vocabulary: an SLI is a measured ratio (for example good requests divided by total), an SLO is the target (say 99.5 percent over 30 days), the error budget is the allowed remainder, and burn-rate alerts page when the budget is being consumed fast (for example 14.4 times the sustainable rate over 1 hour). A proposal in this style would build on `http_server_requests_seconds_count` and the order-completion timer; flag it as a proposal, not as something implemented.

### Example 3: traces across Kafka, and the pipeline from app to Tempo

`management.tracing.enabled` defaults to **off** (`${OMS_TRACING_ENABLED:false}` in `oms-defaults.properties`) with sampling `0.1` when enabled; Kafka template and listener observation are enabled so the `traceparent` header is propagated and a saga appears as one trace across services. Spans leave the app through `micrometer-tracing-bridge-otel` and OTLP/HTTP to the OpenTelemetry Collector (`observability/otel/otel-collector.yml`), which applies a memory limiter, a batch processor and an attribute scrubber before exporting to Tempo:

```yaml
attributes/scrub:
  actions:
    - { key: http.request.header.authorization, action: delete }
    - { key: http.request.header.cookie, action: delete }
    - { key: db.statement, action: delete }
```

The scrubber is defence in depth against credentials or SQL text reaching the trace store. Grafana's datasources are provisioned with links in both directions (`observability/grafana/provisioning/datasources/datasources.yml`): Loki has a derived field that turns `"traceId":"..."` in a log line into a link to Tempo, and Tempo's `tracesToLogsV2` jumps back to the logs filtered by trace id. Sampling is a trade-off: 1.0 in the dev overlay, 0.5 in test, 0.05 in prod. At low sampling the trace you need may not exist, which is why the correlation id (logged for every request) is the primary key for investigation and the trace is the visual aid. A Splunk HEC exporter is present only as a commented, conceptual snippet with the token read from an environment variable.

## Advanced tier

### Example 1: a structured investigation method, applied to "orders are stuck"

The runbooks all share one structure: symptoms, metrics with PromQL, diagnostics commands, a root-cause tree, mitigation, prevention, a practice exercise and regression test pointers. Turn that into a method you can narrate:

1. **Acknowledge and assess impact before diagnosing.** Which services, since when, how many orders (`oms_saga_active`, 5xx ratio). The runbook README's on-call rule: "Stabilise before fixing" (roll back, scale, pause the source), "collect evidence before restarting" (a restart destroys it), "do not edit data by hand if an admin endpoint exists".
2. **Classify the failure.** HTTP healthy plus orders not moving means the *asynchronous* side is sick (runbook 01). Rising 5xx means the synchronous side (runbook 06). Latency up with low CPU suggests a saturated pool or lock waits (runbook 04).
3. **Form hypotheses from the tree, rank them, pick the cheapest discriminating test.**
4. **Get evidence with one id.** For one affected order, start with the correlation id, not the order id:

```bash
curl -s -H "Authorization: Bearer <JWT>" http://localhost:18080/api/v1/orders/<orderId> | jq '{status, statusReason, correlationId, updatedAt}'
curl -s -H "Authorization: Bearer <JWT>" http://localhost:18080/api/v1/admin/saga/<orderId> | jq '{state, currentStep, attempts, lastError, steps}'
grep -h '<correlationId>' .run/*.log | sort
```

`currentStep` and the last `steps[]` entry say which hop did not answer. `RESERVE_INVENTORY` with attempts rising and nothing in inventory's logs points at delivery (outbox, lag, DLT); `lastError` populated points at an orchestrator-visible failure.
5. **Mitigate with the least destructive audited action**, then **verify** the signals recovered, then **review**.

For the order that is stuck, the PromQL in runbook 01 is the dashboard in text form:

```
oms_saga_active                                           # gauge: rising without bound = stuck
rate(oms_order_processing_duration_seconds_count[5m])     # completions per second; 0 while orders arrive = stalled
increase(oms_events_dead_lettered_total[15m])
oms_outbox_pending{service="order-service"}
```

The application also tells you about itself: every service exposes `GET /api/v1/ops/messaging` (`OpsMessagingController`, response record `MessagingStatus`: `consumerGroup`, per-topic `consumerLag` and `committedOffsets`, `processedEvents`, `duplicatesIgnored`, `retries`, `deadLettered`, `outboxPending`, `lastProcessedAt`, `dltTopics`, `lagAvailable`). It degrades to `lagAvailable:false` rather than failing when the Kafka admin client cannot reach the broker, and the runbook says "that itself is a finding". `GET /api/v1/ops/audit` lists audit rows (state changes record who, what, when, correlation id), and the gateway aggregate `GET /api/v1/ops/health` always answers HTTP 200 with each service's status in the body.

### Example 2: the eight runbooks as concise playbooks

| # | Failure | First signals | Decisive checks | Typical root causes | Safe mitigation |
|---|---|---|---|---|---|
| 01 | Orders stuck | `oms_saga_active` rising, no completions, 5xx flat | Saga view for the order, outbox pending per service, DLT records | Dependency down, outbox relay stuck, lost event, compensation not finished | Fix dependency (recovery job re-drives), admin retry, reconciliation; never edit `orders.status` by SQL |
| 02 | Consumer lag | Lag growing, processed rate zero | `kafka-consumer-groups --describe`, retries, outbox pending | No consumers, hot key or poison record, slow handler, rebalance loop | Scale to partition count (3 max useful), fix slow dependency, DLT poison; never reset offsets without approval |
| 03 | Reservation failures | Spike of `InventoryReservationFailed`, or `reserved > on_hand` | Invariant SQL (must return zero rows), lock waits | Demand over stock, check-then-act bug, deadlock from unsorted lines | Case A restock via audited endpoint; Case B (SEV1) pause intake, collect evidence, repair via reconciliation |
| 04 | DB latency | Hikari pending above 0, p99 up, CPU low | `pg_stat_activity`, `idle in transaction`, blocking pids, `pg_stat_statements` | Remote call inside a transaction, lock waits, missing index, bloat, retry storm | Capture then cancel offender, roll back release, shed load; do not just raise the pool size |
| 05 | Duplicate events | `oms_events_duplicates_total` spike, double emails | `processed_events` rows, unique constraints | Redelivery after rebalance (benign), producer bug, missing Idempotency-Key reuse | Benign: nothing; never delete `processed_events` rows to "retry" |
| 06 | Service unavailable | 503/504, `up == 0`, NotReady pods | Direct probes, `kubectl describe`, `--previous` logs, breaker state | Bad config or secret (fail-fast is intended), OOMKilled, DB or Kafka down | Roll back last deploy, restore dependency, never relax the secret check |
| 07 | Failed deployment | Rollout stalls, CrashLoopBackOff | Rollout history, new pod events, `flyway_schema_history` | Image pull, missing secret, Flyway checksum or ordering, probe timing | `rollout undo` first; destructive migrations need DBA and fix-forward |
| 08 | JVM memory | OOMKilled (137), heap near max, GC pauses | `jstat -gcutil`, class histogram, heap dump from one pod | Unbounded cache or list, large payloads, off-heap, limit vs `MaxRAMPercentage` | Capture evidence before restart, raise limit and percentage together as a stopgap, fix and add a test |

Severity guide (`docs/runbooks/README.md`): SEV1 is customer-facing outage or data-integrity loss (page immediately, updates every 15 minutes); SEV2 major degradation (start within 15 minutes, updates every 30); SEV3 minor, no data loss; SEV4 cosmetic. Escalate one level if customer data exposure is suspected, if the issue is not understood after 30 minutes, or if two services are affected.

### Example 3: consumer lag, pool exhaustion and JVM memory in depth

**Consumer lag** is the difference between the log-end offset and the group's committed offset, per partition. Read it per partition: lag on all partitions with an empty `CONSUMER-ID` means no consumers; lag on one partition means a hot key (all events for one `orderId` share a partition because the key is the order id) or a poison record at the head; consumers alive but slow means a slow handler or a rebalance loop caused by exceeding `max.poll.interval.ms`. Two design facts shape the answers. First, the partition count (3) caps useful consumers per group, which is why `infrastructure/kubernetes/keda/scaledobject-kafka-lag.yaml` sets `maxReplicaCount: 3` with the comment "extra consumers would sit idle". Second, a failing record is retried with backoff (500 ms doubling to 5 s, 4 attempts) before going to the DLT, so a poison record blocks *its partition* for several seconds per attempt; malformed JSON skips retries and goes straight to the DLT (verified by `ConsumerKafkaTest`). The outbox changes the diagnosis: if `oms_outbox_pending` grows while consumers are idle, the *producer-side relay* or the broker is the problem, and the events are delayed, not lost, because unpublished rows stay in the database.

**Pool exhaustion** (runbook 04). Little's law: connections needed is roughly request rate times time each request holds a connection. The classic cause in this architecture is holding a connection while doing remote I/O; the mentoring guide (`docs/mentoring/performance.md`, section 2) says "Transactions hold connections: do not call out inside" because the order-to-inventory REST call has a 3 second read timeout times retries. Diagnosis is a thread dump: many threads in `HikariPool.getConnection` while others wait in a socket read means connections are held during a remote call.

```bash
jcmd <pid> Thread.print > threads.txt
grep -c 'HikariPool.*getConnection' threads.txt          # threads waiting for a DB connection
```

Take two dumps 10 seconds apart; a thread in the same frame in both is stuck, not busy. Raising the pool size "moves the queue into the database" (runbook 04); fix the transaction scope instead. Add `idle_in_transaction_session_timeout` and `lock_timeout` as guardrails and `CREATE INDEX CONCURRENTLY` for missing indexes.

**JVM memory** (runbook 08). Healthy heap is a sawtooth returning to a stable floor; a floor that creeps up across GCs is a leak (watch `jvm_gc_live_data_size_bytes`). Heap fine but pod OOMKilled means off-heap, thread stacks or a limit too close to `-Xmx`; the images use `-XX:MaxRAMPercentage=75.0` and `-XX:+ExitOnOutOfMemoryError` (see `infrastructure/docker/order-service.Dockerfile`) so a container restarts cleanly rather than limping. Evidence order: `jstat -gcutil`, then `jcmd 1 GC.class_histogram`, then a heap dump from one pod removed from traffic, analysed in Eclipse MAT's dominator tree. Treat dumps as confidential. In this repo, likely leak suspects are named in the tree: the in-memory rate-limiter buckets when Redis is disabled, product caches without a size bound, and unbounded list endpoints (see `docs/code-review/06-unbounded-pagination-n-plus-one.md`).

## How this repository implements it

| Concept | Where in repo | Notes |
|---|---|---|
| Correlation id | `services/platform-common/.../web/CorrelationIdFilter.java`, `CorrelationIds.java` | Validated, MDC, response echo |
| Kafka correlation | `.../messaging/KafkaConsumerConfigSupport.java`, `EventRouter.java` | Header to MDC for each record |
| JSON logs | `services/platform-common/src/main/resources/logback-spring.xml` | Profile `json-logs` |
| Log hygiene | `.../observability/LogMasking.java`, `LogMaskingTest.java` | Email, token, newline masking |
| Metrics | `.../messaging/MessagingMetrics.java`, `.../observability/Metrics.java`, `OrderMetrics.java` | Contract names |
| Scrape and rules | `observability/prometheus/prometheus.yml`, `observability/prometheus/rules/oms-alerts.yml` | 12 alerts |
| Dashboards | `observability/grafana/build-dashboards.mjs`, `dashboards/*.json` | 5 generated dashboards |
| Traces | `observability/otel/otel-collector.yml`, `observability/tempo/tempo.yml` | Off by default in apps |
| Logs pipeline | `observability/loki/loki.yml`, `promtail.yml` | Only `level` is a label |
| Ops endpoints | `.../messaging/OpsMessagingController.java`, `.../audit/OpsAuditController.java` | Lag, DLT, outbox, audit |
| K8s monitoring | `infrastructure/kubernetes/monitoring/*.yaml` | Generated rules, ServiceMonitor |
| Runbooks | `docs/runbooks/` (runbooks 01 to 08) | Illustrative commands |
| Static checks | `scripts/check-observability.py` | Structure only, not PromQL semantics |
| PIR template | `docs/governance/post-incident-review-template.md` | Blameless |
| Decision record | `docs/adr/0011-observability-stack.md` | Loki chosen over ELK on cost |

### Demonstration-only vs production-grade

Production-grade ideas: correlation id end to end including Kafka; JSON logs with a fixed field set; masking helpers with tests; low-cardinality labelling discipline; alerts as code with one source of truth; ops endpoints that degrade instead of failing; runbooks with root-cause trees and regression-test pointers; attribute scrubbing in the collector. Demonstration-only or unverified: nothing has run against live Prometheus, Loki or Tempo; tracing defaults to off; Tempo and Loki are single-binary with filesystem storage and short retention; the collector uses plaintext in-network; `/actuator/prometheus` is unauthenticated by contract and relies on NetworkPolicy; thresholds are untuned; histogram buckets are configured for only two of five JVM services; there are no SLOs or burn-rate alerts; the `OmsSagasStuck` rule is a heuristic; Promtail needs the Docker socket in Compose (laptop use only); and the earlier runbook text predates some classes that now exist (the saga orchestrator, recovery job and reconciliation are on disk), so confirm endpoint names before relying on them.

## Common mistakes and gotchas

* **Logging the order id but not the correlation id.** The order id appears only where a developer remembered it; the correlation id is on every line.
* **MDC leaks on pooled threads.** Always clear in `finally`; for async or Kafka threads, set and clear per record.
* **High-cardinality labels.** `orderId` as a metric tag or a Loki label creates millions of series or streams.
* **Alerting on causes instead of symptoms**, or without `for:`, trains people to ignore pages.
* **Percentile panels with no histogram.** Quantile alerts silently return no data; test that alerts can fire.
* **Averages.** A mean latency hides the slow tail; use p95 and p99.
* **Restarting first.** It erases the thread dump, heap and logs you needed; collect evidence, then restart.
* **Raising pool sizes or timeouts to hide a slow dependency.** It moves the queue; fix transaction scope and the slow query.
* **Resetting consumer offsets in production.** It skips or replays saga steps; it needs approval, a stopped consumer group and `--dry-run` first.
* **Deleting `processed_events` rows to retry an event.** It re-enables duplicates; use audited admin retry.
* **Editing `orders.status` with SQL.** It bypasses history, audit and the outbox event.
* **Sampling too low to find the trace**, with no log-based fallback.
* **PII in telemetry.** Emails, tokens, SQL text and addresses leak into logs, spans and heap dumps; mask and scrub.
* **Trusting unexecuted runbooks.** Every command here is illustrative; rehearse before an incident (story S11.2 plans it).
* **Blaming people in the review.** The template forbids "X should have known"; actions must be preventive or detective, with an owner and a date.

## Quick-recall cheat sheet

* Three signals: metrics (what and how much), logs (what exactly), traces (where the time went); the correlation id ties them.
* RED for services (rate, errors, duration); USE for resources (utilisation, saturation, errors).
* Alert on symptoms, ratios and `for:` windows; severity means page or ticket; keep one source of truth for rules.
* SLI is a measurement, SLO the target, error budget the allowance; burn-rate alerts page on fast consumption (proposed here, not implemented).
* Key metrics: `oms_events_{processed,duplicates,retries,dead_lettered}_total`, `oms_outbox_pending`, `oms_saga_active`, `oms_saga_compensations_total`, `hikaricp_connections_{active,pending,max}`, `jvm_memory_used_bytes`.
* Investigation order: impact, classify (sync vs async vs resource), hypothesis tree, one-id evidence, least destructive mitigation, verify, review.
* Lag: per partition; max useful consumers equals partitions (3); poison blocks its partition; outbox pending means the producer side.
* Pool exhaustion: thread dump for `HikariPool.getConnection`; remote call inside a transaction is the usual cause.
* Memory: floor creeping up is a leak; collect histogram and dump before restarting; limit versus `MaxRAMPercentage`.
* Never in an incident: reset offsets, edit status by SQL, delete idempotency markers, paste tokens or emails into chat.
* Honest status: configs statically checked only, tracing off by default, runbook commands unexecuted, histograms on two services.

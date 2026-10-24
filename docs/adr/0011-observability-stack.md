# ADR-0011: Observability stack (Prometheus, Grafana, Loki, Tempo, OpenTelemetry)

* Status: Accepted
* Date: 2026-10
* Deciders: platform architecture, operations

## Context

An asynchronous, multi-service flow fails in ways no single log shows: a stuck saga, a growing consumer lag, a duplicate storm. Operators need metrics for symptoms, logs for detail and traces for causality, linked by one correlation id.

## Decision drivers

* Vendor-neutral instrumentation (OpenTelemetry, Micrometer).
* Open-source backends that run in Compose and Kubernetes.
* Cost control: logs and traces are sampled or retained briefly.
* Privacy: no tokens, passwords or full emails in telemetry.

## Considered options

1. Prometheus + Grafana + Loki + Tempo behind an OpenTelemetry Collector (chosen).
2. ELK stack (Elasticsearch, Logstash, Kibana, APM).
3. A commercial SaaS APM.

## Decision outcome

* Metrics: Micrometer with the Prometheus registry on `/actuator/prometheus`. Custom metrics from the contract: `oms.events.processed`, `oms.events.duplicates`, `oms.events.retries`, `oms.events.dead_lettered`, `oms.outbox.pending`, `oms.order.processing.duration`, `oms.saga.active`, `oms.saga.compensations` (`MessagingMetrics`, `Metrics` in `services/platform-common`). Dashboards (provisioned from `observability/grafana/dashboards`): API RED, JVM, HikariCP, messaging, saga funnel. Alert rules in `observability/prometheus/rules/oms-alerts.yml` and `infrastructure/kubernetes/monitoring/prometheusrule.yaml`.
* Logs: JSON via logstash-logback-encoder with `service, correlationId, traceId, spanId`; email masking by `LogMasking`; shipped by Promtail to Loki (`observability/loki`).
* Traces: `micrometer-tracing-bridge-otel` with OTLP export to the collector (`observability/otel/otel-collector.yml`) and Tempo (`observability/tempo/tempo.yml`). Context crosses Kafka through the `traceparent` and `correlationId` headers; the gateway injects `X-Correlation-Id`, which is also echoed to clients and shown in the UI error toasts.
* Operational API: `/api/v1/ops/messaging` (lag, processed, duplicates, retries, DLT counts, outbox pending) and `/api/v1/ops/audit` in every service; `MessagingStatusService` degrades to `lagAvailable:false` rather than failing.
* Scaling signal: KEDA scales consumers on Kafka lag (`infrastructure/kubernetes/keda`).
* Tracing is off locally by default and sampling is configurable.

Status honesty: configuration files, dashboards and rules are statically validated (YAML/JSON parse and CI checks). They have not been observed running against live Prometheus, Loki or Tempo on the dev machine, since Docker is not installed there.

### Consequences

* Good: portable instrumentation, three signals correlated by ids, no licence cost, alerts as code.
* Bad: four or more stateful components to run and size; Loki/Tempo retention must be configured or storage grows; label cardinality discipline needed (never label metrics by orderId); sampling can drop the very trace you need; dashboards drift unless regenerated (`observability/grafana/build-dashboards.mjs` is the source).

## Alternatives rejected

* ELK: heavier to operate, stronger full-text search; Loki is cheaper because it indexes labels only.
* Commercial APM: fastest to adopt but cost and data-residency concerns and cannot be bundled in the reference repo.

## Links

* Contract section 10 and 5.5; `observability/`, `infrastructure/compose/docker-compose.observability.yml`
* `docs/architecture/delivery-semantics.md` (metrics per failure window)

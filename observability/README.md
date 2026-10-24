# OMS Observability

Status: configs authored and statically checked (`python -I scripts/check-observability.py`: JSON parses, rule structure, balanced PromQL, YAML parses). **Not executed against a running stack** (no Docker on the author machine, `promtool` unavailable). No dashboard screenshots or measured values are included.

## 1. Layout

| Path | Purpose |
|---|---|
| `prometheus/prometheus.yml` | scrape config for the no-Docker local run (localhost ports 18080-18084) |
| `prometheus/prometheus.compose.yml` | same, with Compose DNS names |
| `prometheus/rules/oms-alerts.yml` | 12 alert rules (availability, RED, messaging, saga, resources) |
| `grafana/build-dashboards.mjs` -> `grafana/dashboards/*.json` | 5 dashboards generated from one script (edit the script, regenerate) |
| `grafana/provisioning/` | datasources (Prometheus, Loki, Tempo with log<->trace links) and dashboard provider |
| `otel/otel-collector.yml` | OTLP in, scrub processor, Tempo out; Splunk HEC exporter as a **commented conceptual** snippet |
| `tempo/tempo.yml`, `loki/loki.yml`, `loki/promtail.yml` | local single-binary traces and logs |

Run: `docker compose -f infrastructure/compose/docker-compose.yml -f infrastructure/compose/docker-compose.observability.yml up` (see compose file headers). Kubernetes equivalents (ServiceMonitor, PrometheusRule) are in `infrastructure/kubernetes/monitoring/`.

## 2. Three pillars and how they connect

* **Metrics**: Micrometer -> `/actuator/prometheus` -> Prometheus (pull). Dashboards + alerts.
* **Traces**: Micrometer Tracing (OTel bridge) -> OTLP/HTTP -> OTel Collector -> Tempo. Kafka `traceparent` header propagation keeps a saga in one trace across services. Sampling is configurable (`MANAGEMENT_TRACING_SAMPLING_PROBABILITY`; 1.0 in the demo overlay, use 0.05-0.1 under real load).
* **Logs**: JSON to stdout (profile `json-logs`) -> Promtail -> Loki. Every line carries `correlationId`, `traceId`, `spanId`; Grafana turns `traceId` into a link to Tempo, and Tempo can jump back to logs. Searching one order end-to-end: `{service=~".+"} | json | correlationId="<id>"`.

## 3. Metric names (assumptions to verify against the built services)

Micrometer dotted names become Prometheus snake_case; counters get `_total`, timers become `_seconds_{count,sum,bucket,max}`.

| Contract name (section 7) | Prometheus name used here |
|---|---|
| `oms.events.processed{service,topic,type}` | `oms_events_processed_total` |
| `oms.events.duplicates` | `oms_events_duplicates_total` |
| `oms.events.retries` | `oms_events_retries_total` |
| `oms.events.dead_lettered` | `oms_events_dead_lettered_total` |
| `oms.outbox.pending` (gauge) | `oms_outbox_pending` |
| `oms.order.processing.duration` (timer) | `oms_order_processing_duration_seconds_*` |
| `oms.saga.active` (gauge) / `oms.saga.compensations` | `oms_saga_active` / `oms_saga_compensations_total` |

Standard names used: `http_server_requests_seconds_{count,sum,bucket}` (labels `status`, `uri`), `hikaricp_connections_{active,idle,pending,max}` and `hikaricp_connections_{acquire,usage}_seconds_*`, `jvm_memory_used_bytes{area}`, `jvm_gc_pause_seconds_*`, `kafka_consumer_fetch_manager_records_lag_max{topic}`.

Required app settings for histogram panels/alerts (p95/p99): `management.metrics.distribution.percentiles-histogram.http.server.requests=true` and the same for `oms.order.processing.duration`. Without them, p95 panels are empty (counts/sums still work). A `service` label on `oms_*` series is assumed to come from `management.metrics.tags.service=${spring.application.name}`; the Prometheus `job` label also identifies the service.

## 4. Log stack choice: Loki + Promtail (not Elasticsearch/Kibana)

Chosen because: (1) the logs are already structured JSON, and Loki indexes only labels (`service`, `level`), so memory/disk cost on a laptop is a fraction of Elasticsearch; (2) it shares Grafana with metrics and traces, giving one UI and the log-to-trace link; (3) one fewer JVM cluster to operate. Tradeoff: no full-text inverted index, so broad free-text searches over long ranges are slower than Elasticsearch, and high-cardinality fields (correlationId, traceId) are deliberately left in the line, not labels. If compliance needs long retention, rich analytics or SIEM, pick Elasticsearch/OpenSearch or Splunk instead; the JSON log format does not change.

**Splunk (conceptual, not implemented/tested):** the collector config contains a commented `splunk_hec` exporter showing how logs/traces could be forwarded to a Splunk HTTP Event Collector, with the token read from an environment variable. It is disabled and labelled conceptual.

## 5. Alerts

`OmsServiceDown, OmsHighErrorRate, OmsHighP95Latency, OmsConsumerLagHigh, OmsDeadLetterRecords (DLT>0), OmsOutboxBacklog, OmsRetryStorm, OmsSagasStuck, OmsCompensationsSpike, OmsOrderProcessingSlow, OmsHikariPoolExhaustion, OmsJvmHeapHigh`. Thresholds are starting points, not tuned on measurements. `OmsSagasStuck` is a heuristic on `oms_saga_active` (a per-saga "stuck" metric does not exist in the contract; the authoritative check is `/api/v1/admin/saga/{orderId}` and the `SagaRecoveryJob`).

## 6. Security notes

Prometheus/Grafana bind to 127.0.0.1 only; Grafana admin password comes from `.env` (`GRAFANA_ADMIN_PASSWORD`, no default); anonymous access off. `/actuator/prometheus` is unauthenticated by contract, so in Kubernetes it is protected by NetworkPolicy (only the monitoring namespace may reach it) and never routed via the gateway/ingress. Promtail needs the Docker socket in Compose (read-only mount, still sensitive): laptop only.

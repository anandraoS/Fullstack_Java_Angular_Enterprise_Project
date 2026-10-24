# Integration defects found by the live run (2026-10-18)

Every module was green in isolation (896 unit + 61 integration test cases at the start). The defects below were found only by running the
whole stack (embedded Kafka + 4 services + gateway + built Angular UI, no Docker) and driving it through the gateway, the browser and the
metric/log endpoints. Each fix lives in the owning module, has a regression test where one is practical (stated per item), and was
verified again on the live stack afterwards. "Severity" is my judgement for a production system.

| # | Area | Severity | Found by |
|---|---|---|---|
| D-01 | scripts/run-local.sh readiness probe | High (stack never starts on this box) | first `run-local.sh` run |
| D-02 | gateway concurrency limit | High | 30 parallel orders (`oversell-live.mjs`) |
| D-03 | Angular order page | High (blank page) | Playwright live run, spec 4 |
| D-04 | e2e mock ids / spec wording | Low | Playwright live run, specs 7 and 16 |
| D-05 | failure-path counters absent | Medium | `check-metric-names.mjs` |
| D-06 | Kafka client metrics not bound | Medium | `check-metric-names.mjs` |
| D-07 | latency histograms missing on 3 of 5 apps | Medium | `check-metric-names.mjs` |
| D-08 | no lifecycle logs in order/fulfillment; no correlation id in scheduler logs | Medium | correlation trace |
| D-09 | K8s base config defaulted to profile `dev` | High (security) | security flag 6a |
| D-10 | `/v3/api-docs` served in `prod` | Medium (security) | security flag 6b |
| D-11 | run-local readiness timeout too short | Low | first run |

## D-01 `wait-for.mjs` crashed Node on Windows, `run-local.sh` aborted a healthy stack
* **Symptom:** `run-local.sh` printed `Assertion failed: !(handle->flags & UV_HANDLE_CLOSING), file src\win\async.c, line 76` and
  `fulfillment-service did not become ready on port 18083`, then stopped everything, although the service was up.
* **Root cause:** `scripts/lib/wait-for.mjs` called `process.exit(0)` immediately after a `fetch()` that used `AbortSignal.timeout()`;
  on Windows with Node 24 this trips a libuv assertion (exit code 134), which the caller read as "not ready".
* **Fix:** the probe uses `node:http` with a socket timeout and sets `process.exitCode` instead of calling `process.exit()`.
* **Test:** no automated regression test (it is a Windows/Node runtime interaction). Evidence: `run-local.sh` started the stack three times afterwards
  and `node scripts/lib/wait-for.mjs http <url> 3` exits 0/1 cleanly.

## D-02 Gateway shed load at 26 concurrent requests per downstream service (503 `UPSTREAM_UNAVAILABLE`)
* **Symptom:** in the first 30-parallel-order burst, 29 requests got 202 and one got `503 UPSTREAM_UNAVAILABLE`; the gateway log said
  `Upstream unavailable: BulkheadFullException`. The 30 requests spanned only ~1.8 s, nothing was down.
* **Root cause:** Spring Cloud CircuitBreaker's reactive Resilience4j integration always wraps the circuit-breaker filter in a *semaphore
  bulkhead* with the library default of 25 concurrent calls and no waiting (`ReactiveResilience4JAutoConfiguration` creates the provider
  even though nothing configures it). The limit was an invisible hard cap per route.
* **Fix:** `GatewayProperties.Bulkhead` (`oms.gateway.bulkhead.max-concurrent-calls`, env `OMS_GATEWAY_BULKHEAD_MAX_CALLS`, default 200;
  `max-wait` default 500 ms) applied through a `Customizer<ReactiveResilience4jBulkheadProvider>` in `RoutesConfig`; explicit
  `resilience4j-bulkhead` dependency in `gateway/pom.xml`.
* **Test:** `BulkheadConcurrencyTest` (60 parallel requests to a stub that answers after 600 ms, all must be 200). I confirmed it fails
  with the old limits (`-Doms.gateway.bulkhead.max-concurrent-calls=25 -Doms.gateway.bulkhead.max-wait=0ms` produced 503s) and passes with the fix.
  Live re-run: 30/30 accepted.

## D-03 Order detail page crashed (blank page, endless spinner) for orders rejected at validation
* **Symptom:** Playwright spec "insufficient stock (SKU-1020)" timed out: the status chip stayed empty. Browser console:
  `TypeError: Cannot read properties of null (reading 'toFixed')`.
* **Root cause:** contract section 13 says `sku/name/unitPrice/lineTotal` are `null` until validation has priced the order and stay null if it
  is rejected there. The UI model typed them as non-null and `order-detail.ts` called `i.unitPrice.toFixed(2)`. The mock backend priced everything, so
  no earlier test hit it.
* **Fix:** `OrderItem` fields are `| null` in `core/models.ts`; `OrderDetail.money()` renders `-` for null prices.
* **Test:** new `order-detail.spec.ts` (renders a REJECTED order with null prices, expects status chip, reason and `-` cells). Frontend unit tests: 149 pass
  (148 before). Live re-run of the spec: pass.

## D-04 e2e assets were written against the mock only
* The mock backend used the invalid product id pattern `p0000000-...` (contract section 9 already corrected it to `a0000000-...0010NN`); fixed in
  `tests/e2e/mock-backend/server.js` and in `docs/runbooks/03-...md`.
* Spec wording that differed on the real backend and is now accepted in both modes: retrying a TERMINAL failed order is `409 INVALID_STATE_TRANSITION`
  on the real order-service (contract section 13) and the UI shows "That action is not possible in the current state of the order"; notification
  subjects are `Order <id8> failed` rather than `Order failed`. Both modes pass (mock 18/18, live 18/18).

## D-05 `oms_events_retries_total`, `oms_events_dead_lettered_total`, `oms_events_duplicates_total` did not exist on a healthy service
* **Root cause:** Micrometer registers a counter on its first increment. Dashboards showed "No data" and `increase(...)>0` alert expressions had no series.
* **Fix:** `MessagingMetrics.preRegister(topics)` creates the series at 0 for every topic in `oms.messaging.topics`, called from `OmsMessagingAutoConfiguration`.
* **Test:** `MessagingMetricsTest` (3 cases). Live: the three names now appear on all four services.

## D-06 `kafka_consumer_fetch_manager_records_lag_max` (consumer-lag alert and dashboard) was never exposed
* **Root cause:** Spring Boot binds Kafka client metrics only to the consumer factory it auto-configures; platform-common builds its own
  `DefaultKafkaConsumerFactory`, so no `kafka_consumer_*` meters existed.
* **Fix:** `KafkaConsumerConfigSupport.setMeterRegistry` adds a `MicrometerConsumerListener`.
* **Test:** no dedicated unit test; verified live (`scripts/check-metric-names.mjs`: present on ports 18081-18084 with `topic`/`partition` labels).
  Note: Micrometer rewrites dots in that label (`oms_inventory_events_v1`) while `oms_events_*` keep dots (`oms.inventory.events.v1`); not changed.

## D-07 Latency histogram buckets existed only on order- and inventory-service
* **Root cause:** `percentiles-histogram.http.server.requests` was set in two services' own yml, so the p95/p99 panels and `OmsHighP95Latency` had no data for
  fulfillment, notification and the gateway; `spring_kafka_listener_seconds_bucket` (messaging dashboard) did not exist anywhere.
* **Fix:** platform-common `oms-defaults.properties` enables `http.server.requests` and `spring.kafka.listener` histograms for all servlet services; the gateway
  sets `http.server.requests` in its own `application.yml`.
* **Test:** `PlatformDefaultsTest`; live: buckets on all five ports.

## D-08 order-service and fulfillment-service logged nothing about an order's lifecycle
* **Symptom:** the correlation trace of one order contained only gateway, inventory and notification lines.
* **Root cause:** no INFO logging on the success path in the two services that own the saga and the fulfillment; additionally the fulfillment scheduler
  thread has no MDC, so any line it wrote would have had no correlation id.
* **Fix:** `OrderTransitions` logs every transition (and creation) with the MDC correlation id; `FulfillmentService` logs acceptance and each step with the stored
  correlation id, set into the MDC around the line (and restored).
* **Test:** `OrderTransitionsLoggingTest`, `FulfillmentServiceTest.schedulerStepsLogTheStoredCorrelationIdEvenWithoutMdc`. Live: `docs/validation/correlation-trace-sample.md`
  now shows all five services.

## D-09 / D-10 Security flags (see `docs/security/threat-model.md` section 10)
* D-09 `infrastructure/kubernetes/base/configmap.yaml` defaulted `SPRING_PROFILES_ACTIVE` to `dev,json-logs`. Base is now `json-logs`; dev/test overlays set
  `dev,json-logs` explicitly; prod overlay unchanged (`prod,json-logs`). Static validation only (no cluster).
* D-10 `springdoc.api-docs.enabled` was never disabled in prod (and `swagger-ui` was not disabled in order-service's prod block either). Now both are `false` in the
  `prod` profile of all four services; test `ProdProfileDocsDisabledTest` x4 (config-level proof, not an HTTP 404 from a prod-profile process: prod needs PostgreSQL).

## D-11 `run-local.sh` waited 180 s per service and never checked for busy ports
* Starting six JVMs at once on this machine takes 108-132 s per service (log: `Started ... in 108.652 seconds`), so a slow start looked like a failure. Timeout raised to 300 s. Second half, found at the very end: when a FOREIGN process (another project's gateway) held port 18080, the health URL answered and the script reported "ready" for a gateway that had failed to bind; the script now refuses to start if any of 19092/18080-18084 is taken (checked with a dummy listener).

## Known issues NOT fixed (honest list)
* Gateway logs one `WARN` from `PrometheusMeterRegistry` (`http.client.requests.active` tag keys conflict with Spring Cloud Gateway's `http_client_requests_active_seconds`);
  the conflicting meter is simply not registered; no functional impact found.
* A separate `traceId` is created at every Kafka hop in this local run (no collector, tracing export off); end-to-end distributed traces were not demonstrated.
* `/actuator/prometheus` and `/actuator/health` are unauthenticated on every service port and on the gateway's public port (threat model 10.4).
* OIDC/JWKS mode is wired in code (gateway + platform-common) but untested; in that mode order -> inventory calls would lack a SERVICE token (threat model 10.3).
* Startup of the whole stack needs ~2.5 minutes on this machine; memory is capped at 384 MB per JVM in `run-local.sh`.

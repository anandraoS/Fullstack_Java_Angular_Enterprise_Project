# Interview questions Q145-Q150

Scope of this part of the question bank: six scenario questions taken from **real defects found while integrating and running this platform end to end** (`docs/validation/integration-defects.md`, items D-01 to D-11). Each one was found by running the real stack, not by reading code, which is the point: unit tests were green and the defect was still there. All data is synthetic. Evidence statements refer to the local no-Docker stack (embedded KRaft Kafka, H2 in PostgreSQL mode) described in `docs/validation/validation-report.md`.

### Q145. Scenario: Under a burst of 30 parallel requests one call got `503 UPSTREAM_UNAVAILABLE`, yet no service was down. How do you find the cause?
**Type:** Scenario   **Level:** Advanced   **Topic:** Resilience4j bulkhead, API gateway, concurrency limits

**Short answer.** Read the gateway log line, not the status code: it said `BulkheadFullException`. Spring Cloud CircuitBreaker's reactive Resilience4j integration wraps the circuit-breaker filter in a semaphore bulkhead with a library default of 25 concurrent calls and no waiting, so the 26th concurrent call to one route was rejected. Make the limit explicit, configurable and tested.

**Detailed answer.** The symptom appeared in the 30-parallel-order oversell run: 29 requests got 202 and one got 503, although the burst lasted only about 1.8 seconds and every service was healthy. Reasoning steps: (1) a 503 from the gateway's own fallback means the circuit breaker path rejected the call, not that the downstream answered 503; (2) the gateway log showed `Upstream unavailable: BulkheadFullException`; (3) nothing in our configuration mentioned a bulkhead, which means a default was in play. `ReactiveResilience4JAutoConfiguration` creates the bulkhead provider even when nothing configures it, so there was an invisible hard cap of 25 per route.

The fix in `gateway/`: a typed property (`oms.gateway.bulkhead.max-concurrent-calls`, default 200, `max-wait` 500 ms) applied by a `Customizer<ReactiveResilience4jBulkheadProvider>` in `RoutesConfig`, plus an explicit `resilience4j-bulkhead` dependency. The regression test `BulkheadConcurrencyTest` sends 60 parallel requests to a stub that answers after 600 ms and requires all to succeed; it was shown to fail when the old limits were passed back in (`max-concurrent-calls=25`, `max-wait=0ms`) and to pass with the fix. Live re-run: 30 of 30 accepted, 3 FULFILLED and 27 REJECTED (stock was 3).

**Trade-offs and failure modes.**
* A bulkhead is a feature (it protects the gateway from a slow dependency eating all threads); the defect was that it was hidden, not that it existed.
* A larger limit moves the protection point: size it from measured downstream capacity, and keep a short `max-wait` so overload fails fast.
* Unit tests with a handful of requests cannot reveal a concurrency cap; only a parallel test above the default can.

**Likely follow-ups.**
* Semaphore versus thread-pool bulkhead? Semaphore is cheap and fits a non-blocking gateway; thread-pool isolation adds a queue and context switches.
* How would you alert on it? Count 503s by route and the bulkhead's available-concurrent-calls gauge; this project did not add that alert (open item).

### Q146. Scenario: The order detail page was a blank screen with an endless spinner for orders rejected at validation. How did it escape, and how do you stop it recurring?
**Type:** Scenario   **Level:** Intermediate   **Topic:** Frontend null handling, contract testing, mock versus real backend

**Short answer.** The API contract says line-item prices are `null` until validation prices the order and stay `null` if the order is rejected there. The Angular model typed them as non-null and the template called `unitPrice.toFixed(2)`, which threw. The mock backend priced every order, so no test ever saw a null. Fix the type, render a placeholder, add a spec for the rejected order, and test against the real backend.

**Detailed answer.** The Playwright spec "insufficient stock (SKU-1020)" timed out on the live run; the browser console showed `TypeError: Cannot read properties of null (reading 'toFixed')`. Root cause: the shared contract (section 13) allows `sku`, `name`, `unitPrice` and `lineTotal` to be null on an unpriced or rejected order; `frontend/oms-ui/src/app/core/models.ts` declared them as `number`/`string`. The fix made the fields `| null` (so the compiler now forces handling) and `OrderDetail.money()` renders `-` for null. A new `order-detail.spec.ts` renders a REJECTED order with null prices and expects the status chip, the reason and the `-` cells; frontend unit tests went from 148 to 149 and the live Playwright run reached 18 of 18.

The deeper lesson is about test doubles: a mock that is more generous than the real service hides exactly the edge cases that matter. The mock backend had also used an invalid product id pattern. Mitigations: generate the mock from the OpenAPI export (`specs/openapi/`), run the same e2e specs in mock and live mode (this project does), and keep the failure paths (rejection, 409, 429) in the mock.

**Trade-offs and failure modes.**
* `strictNullChecks` only helps if the model type tells the truth; types copied from happy-path examples lie.
* A global error boundary would have shown an error instead of a blank page; the page should fail visibly, not silently.

**Likely follow-ups.**
* Why not give prices a default of 0? A zero price is wrong data presented as right data.
* How would a contract test help? Validate recorded real responses against the schema used to type the client.

### Q147. Scenario: The dead-letter and retry alerts showed "No data" on a perfectly healthy service. Why, and what did you change?
**Type:** Scenario   **Level:** Intermediate   **Topic:** Micrometer lazy meter registration, alerting on absent series

**Short answer.** Micrometer registers a counter on its first increment, so `oms_events_retries_total`, `oms_events_dead_lettered_total` and `oms_events_duplicates_total` simply did not exist until the first failure. Dashboards were empty and `increase(...) > 0` rules had no series to evaluate. Pre-register the counters at zero for every configured topic.

**Detailed answer.** `scripts/check-metric-names.mjs` compared every metric name used in the five dashboards and twelve alert rules with a live scrape of all `/actuator/prometheus` endpoints (313 series names). The first run listed 5 missing names. For the three counters above the cause was lazy registration; `MessagingMetrics.preRegister(topics)` now creates the series at 0 for each topic in `oms.messaging.topics`, called from `OmsMessagingAutoConfiguration` (`MessagingMetricsTest`, 3 cases). Two siblings were different bugs found by the same script: `kafka_consumer_fetch_manager_records_lag_max` never existed because platform-common builds its own `DefaultKafkaConsumerFactory`, which Boot does not bind to the registry (fix: add a `MicrometerConsumerListener`), and the latency histogram buckets existed on only two of five applications (see Q148). After the fixes the check reports 0 missing.

**Trade-offs and failure modes.**
* `absent()` alerts catch a missing series but also fire on a restart; pre-registration is cleaner for counters.
* Series created at zero look like "healthy"; keep a separate alert for a scrape target that is down (`up == 0`).
* Static linting of dashboards (`check-observability.py`) passed all along: it validated JSON shape, not that the data exists.

**Likely follow-ups.**
* How do you test an alert rule itself? `promtool test rules` with synthetic series; not executed in this project (no Prometheus ran).
* Why label cardinality matters here: topic is a bounded label, order id would not be.

### Q148. Scenario: The p95 latency alert never fired for three of five applications. What was wrong?
**Type:** Scenario   **Level:** Intermediate   **Topic:** Histogram buckets, `histogram_quantile`, platform defaults

**Short answer.** p95 and p99 come from `histogram_quantile` over `*_bucket` series, and Micrometer only publishes those when `management.metrics.distribution.percentiles-histogram.http.server.requests=true`. That property was set in only the order and inventory service configs, so fulfillment, notification and the gateway had no buckets and the panels and `OmsHighP95Latency` had no data. Move the setting to a shared default and verify on every port.

**Detailed answer.** The fix is in `services/platform-common/src/main/resources/META-INF/oms-defaults.properties` (loaded by `OmsDefaultsEnvironmentPostProcessor`) for `http.server.requests` and `spring.kafka.listener`, so every servlet service inherits it, and the reactive gateway sets `http.server.requests` in its own `application.yml`. `PlatformDefaultsTest` asserts the default is present; the live check confirmed `http_server_requests_seconds_bucket` on all five ports. A related note: percentiles computed client-side (`publishPercentiles`) cannot be aggregated across replicas, which is why the platform publishes buckets and computes quantiles in Prometheus.

**Trade-offs and failure modes.**
* Histograms multiply series (buckets x tags x instances): restrict to the meters whose SLO you alert on, and keep tags bounded (URI templates, not raw paths).
* Shared defaults reduce drift but hide behaviour: document them and test them (`PlatformDefaultsTest`).

**Likely follow-ups.**
* Why not average the per-instance p95? Quantiles do not average; merge buckets first, then compute.
* How would you pick bucket boundaries? Use SLO boundaries (`management.metrics.distribution.slo`) around the latency objective.

### Q149. Scenario: `run-local.sh` aborted a healthy stack with "service did not become ready", and later reported "ready" for a service that had failed to start. Explain both.
**Type:** Scenario   **Level:** Intermediate   **Topic:** Startup scripts, readiness probes, cross-platform tooling

**Short answer.** The first was a Windows-specific crash: the Node readiness probe called `process.exit(0)` right after a `fetch()` that used `AbortSignal.timeout()`, which tripped a libuv assertion (exit code 134) that the shell script read as "not ready". The second was a port clash: a different project's process already held port 18080 and answered the health URL, so the script declared success for a gateway that had failed to bind.

**Detailed answer.** (D-01) `scripts/lib/wait-for.mjs` now uses `node:http` with a socket timeout and sets `process.exitCode` instead of calling `process.exit()`; the stack then started cleanly four times. There is no automated regression test because it is a Windows plus Node 24 runtime interaction; the evidence is repeated clean starts and the probe's clean exit codes. (D-11) Starting six JVMs at once takes 108 to 132 s each on the development machine, so the per-service timeout was raised from 180 s to 300 s, and the script now refuses to start when any of ports 19092 and 18080 to 18084 is already in use (verified with a dummy listener). Lessons: a readiness probe must identify *your* process (check the PID or a build id in the response, not just HTTP 200), failures should print the underlying exit code and log tail, and timeouts must be sized from measured startup, not guessed.

**Trade-offs and failure modes.**
* Treating any non-zero probe exit as "not ready" conflates tool failure with service failure; distinguish exit codes.
* Port pre-checks are racy (time of check versus time of use) but catch the common case cheaply.

**Likely follow-ups.**
* What does Kubernetes do differently? Probes belong to the kubelet and a pod has its own network namespace, so port clashes between projects cannot occur.
* How do you test shell tooling? Smoke tests plus injected faults (the dummy-listener test); a real CI run on Windows was not done.

### Q150. Scenario: The Kubernetes base manifests defaulted to the `dev` profile and the production API docs were reachable. What was the risk and how was it closed?
**Type:** Scenario   **Level:** Advanced   **Topic:** Spring profiles, secure defaults, Kustomize overlays

**Short answer.** The base ConfigMap set `SPRING_PROFILES_ACTIVE=dev,json-logs`, so any environment that forgot to override it would run the `dev` profile, which enables the demo token endpoint and seed data. Separately, `springdoc` was not disabled in the `prod` profile, so `/v3/api-docs` would be served in production. Secure defaults mean the base is the safe state and each less-safe environment opts in.

**Detailed answer.** (D-09) The base ConfigMap now sets `json-logs` only; the dev and test overlays set `dev,json-logs` explicitly and the prod overlay sets `prod,json-logs`; `kustomize build` of each overlay confirmed the rendered values and `scripts/validate-infra.sh` passed (47 PASS, 0 FAIL, 2 SKIP after the final fixes), including `kubeconform -strict`. (D-10) `springdoc.api-docs.enabled=false` and `springdoc.swagger-ui.enabled=false` are now set in the `prod` profile of all four services; `ProdProfileDocsDisabledTest` (one per service) asserts it at configuration level. That is not an HTTP 404 from a real prod-profile process, because prod needs PostgreSQL, which was not available here, and nothing was applied to a cluster. The gateway additionally answers 404 for `/v3/api-docs` and 401 without a token in the dev profile.

**Trade-offs and failure modes.**
* Overlays that override a base value can drift silently; render and assert the final manifests in CI instead of reading the YAML.
* A configuration-level test proves the property, not the whole request path; say so when you present it.
* `/actuator/health` and `/actuator/prometheus` remain unauthenticated (see `docs/known-limitations.md`); restrict them by NetworkPolicy or a management port in a real deployment.

**Likely follow-ups.**
* Why not fail startup if `dev` is active with a production database URL? A good guard, not implemented here.
* How is the demo password handled? Random per run (`scripts/run-local.sh`), never committed.

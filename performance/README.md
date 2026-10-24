# Performance tests (k6)

**No results are included. None of these scripts has been executed** (k6 is not installed on the authoring machine; Docker is not available either). They were only syntax-checked with `node --check` (as ES modules, which validates syntax, not k6 API usage). The thresholds are initial targets to be calibrated on your hardware, not measured guarantees. Do not quote numbers from this repository.

## Scripts

| File | Purpose |
|---|---|
| `order-flow.js` | login -> browse products -> create order (unique `Idempotency-Key` per iteration) -> poll until terminal. `PROFILE=smoke\|load\|soak\|spike\|stress` |
| `oversell.js` | `CONTENDERS` (default 40) customers race for **SKU-1019 (seed stock 3)**. Asserts through the inventory API that at most 3 units end up reserved/shipped overall (stock arithmetic before/after) and that no more than 3 orders obtain stock. Aborts if the SKU is not freshly seeded to the expected stock |
| `lib/common.js` | base URL, login, idempotency key, expected-status handling |

Profiles: **smoke** 1 VU/30s sanity; **load** ramp to 20 VUs for 10 min; **soak** 15 VUs for 2h (`-e SOAK_DURATION=30m`) to expose leaks (heap, Hikari pool, Kafka lag, outbox growth); **spike** 2 -> 80 VUs in 20 s and back; **stress** ramping arrival rate to 120 iter/s to find the knee.

## Running (instructions only)

```bash
# 1. start the system: scripts/run-local.sh   (prints OMS_DEMO_PASSWORD once)  or Docker Compose / a cluster
# 2. install k6 (https://grafana.com/docs/k6/latest/set-up/install-k6/), then:
k6 run -e BASE_URL=http://localhost:18080 -e OMS_DEMO_PASSWORD="<printed password>" -e PROFILE=smoke performance/order-flow.js
k6 run -e OMS_DEMO_PASSWORD="<printed password>" performance/oversell.js          # needs a FRESH seed (SKU-1019 stock 3)
```

Password is read from the environment only; never put it in a file. Prefer a dedicated test environment: the scripts create real orders and consume stock.

## Things to know before interpreting anything

* **Rate limiting**: the gateway rate-limits (429 `RATE_LIMITED`). Load profiles will hit it unless you raise limits for the test environment; 429s are counted (`oms_rate_limited_429`) and not treated as transport failures. Never disable rate limiting in a shared environment to "make the numbers look better".
* **Fulfillment delay**: the simulated step delay (`oms.fulfillment.step-delay-ms`, default 1500) dominates order completion time; lower it in the test environment or the `oms_order_completion_ms` threshold measures the simulation, not the system.
* **Stock exhaustion**: `load`/`soak` consume stock; later iterations will see `REJECTED` orders (insufficient stock) once products run out. Re-seed between runs or adjust stock with the operator API.
* **Embedded H2 vs PostgreSQL**: local no-Docker runs use H2; contention behaviour (row locks, the conditional `UPDATE`) differs from PostgreSQL. Run oversell against the Compose/K8s PostgreSQL stack before drawing conclusions about concurrency safety.
* **Correctness vs speed**: `oversell.js` is a correctness gate and should stay green at any load; a failure means a real concurrency bug, not a tuning issue.
* Observe while running: Grafana dashboards in `observability/` (RED, Kafka lag, Hikari pool, JVM) are how you find the bottleneck; k6 only tells you that there is one.

## Export results to Prometheus (optional)

`k6 run -o experimental-prometheus-rw ...` with `K6_PROMETHEUS_RW_SERVER_URL` pointing at a remote-write receiver (Prometheus started with `--web.enable-remote-write-receiver`).

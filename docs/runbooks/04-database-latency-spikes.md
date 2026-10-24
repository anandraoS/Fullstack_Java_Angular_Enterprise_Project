# Runbook 04 - Database latency spikes

All commands: illustrative, not executed against a live cluster. Local/test databases are H2 (`MODE=PostgreSQL`); the diagnostics below apply to PostgreSQL 16 in Compose/Kubernetes. Each service owns its own database (`order_db`, `inventory_db`, `fulfillment_db`, `notification_db`).

## Symptoms

* `http.server.requests` p99 up across endpoints of one service; CPU of the app low.
* `hikaricp.connections.pending` > 0 and `hikaricp.connections.active` equal to max; log lines `HikariPool-1 - Connection is not available, request timed out after 30000ms`.
* Kafka lag growing on that service's consumers (handlers wait on the DB), `oms.outbox.pending` rising (relay's DB queries slow).
* Readiness may flap if the DB health indicator times out.

## Metrics and PromQL

```
max by (service) (hikaricp_connections_active) / max by (service) (hikaricp_connections_max)
max by (service) (hikaricp_connections_pending)
histogram_quantile(0.99, sum by (le, service, uri) (rate(http_server_requests_seconds_bucket[5m])))
histogram_quantile(0.99, sum by (le, service) (rate(hikaricp_connections_acquire_seconds_bucket[5m])))
rate(oms_events_processed_total[5m])
oms_outbox_pending
```

## Diagnostics

```bash
curl -s localhost:18081/actuator/health | jq '.components.db'
curl -s localhost:18081/actuator/prometheus | grep -E 'hikaricp_connections|jvm_threads_states'
jcmd <pid> Thread.print | grep -c 'HikariPool.*getConnection'     # threads waiting for a connection
```

PostgreSQL (use a read-only ops role; `pg_stat_statements` must be enabled in `shared_preload_libraries` and `CREATE EXTENSION pg_stat_statements` done in the database):

```sql
-- 1. Who is running what, longest first
select pid, state, wait_event_type, wait_event, now()-query_start as age, left(query,120) as query
  from pg_stat_activity
 where datname = 'order_db' and state <> 'idle'
 order by age desc;

-- 2. Idle in transaction (leaked or long transactions hold locks and connections)
select pid, now()-xact_start as xact_age, left(query,120)
  from pg_stat_activity where state = 'idle in transaction' order by xact_age desc;

-- 3. Blocking chains
select pid, pg_blocking_pids(pid) as blocked_by, left(query,80) from pg_stat_activity where cardinality(pg_blocking_pids(pid)) > 0;
select l.locktype, l.mode, l.granted, a.pid, left(a.query,80)
  from pg_locks l join pg_stat_activity a on a.pid = l.pid where not l.granted;

-- 4. Slowest statements by total and mean time (PG 13+ column names)
select calls, round(total_exec_time::numeric,0) as total_ms, round(mean_exec_time::numeric,2) as mean_ms, rows, left(query,100)
  from pg_stat_statements order by total_exec_time desc limit 10;

-- 5. Missing index smell: large sequential scans
select relname, seq_scan, seq_tup_read, idx_scan from pg_stat_user_tables order by seq_tup_read desc limit 10;

-- 6. Connection count vs limit
select count(*) as connections, current_setting('max_connections') as max from pg_stat_activity;
```

Plan check for a suspect query: `EXPLAIN (ANALYZE, BUFFERS) <query>` on a replica or off-peak; never run `ANALYZE` of write statements in production without a transaction you roll back.

## Root-cause tree

```
DB latency
|- Pool exhausted but DB itself fast (pg_stat_activity mostly idle / idle in transaction)
|    |- connections held during remote calls (REST to inventory, 3 s read timeout x retries) -> move call outside the tx
|    |- leaked connection / transaction never closed
|    '- pool too small for the real concurrency (Little's law) or too many replicas x pool > max_connections
|- DB busy
|    |- slow statement (pg_stat_statements): missing index, N+1 burst, unbounded list -> code-review scenario 06
|    |- lock waits: hot rows (stock), long transactions, migrations -> pg_locks / blocking pids
|    |- autovacuum behind: bloated outbox / processed_events / saga_step_log (never purged)
|    '- resource: CPU, IO, memory, storage latency on the node
|- Recent change: new migration (table rewrite, index build without CONCURRENTLY), new query, new release -> runbook 07
'- Traffic: retry storm after an upstream blip multiplies load
```

## Mitigation

1. Cancel/terminate the offender only after capturing it:

```sql
select pg_cancel_backend(<pid>);       -- polite: cancels the query
select pg_terminate_backend(<pid>);    -- last resort: kills the session
```

2. Idle-in-transaction leaks: restart the leaking pod after taking a thread dump; set `idle_in_transaction_session_timeout` as a guardrail.
3. If caused by a release, roll back (runbook 07).
4. Shed load: scale consumers down temporarily (the outbox and Kafka buffer), reduce rate limits at the gateway; do not just raise the Hikari pool size (it moves the queue into the database).
5. Add the missing index with `CREATE INDEX CONCURRENTLY` (outside Flyway's transaction; plan a migration) after confirming with `EXPLAIN`.
6. Purge old data in batches (published outbox rows, old `processed_events`, per policy), never one giant DELETE.

## Prevention

* Remote calls never inside DB transactions; connection timeout and leak-detection threshold set in Hikari.
* Statement-count tests for list endpoints; indexes reviewed with `EXPLAIN` on PostgreSQL (H2 plans differ).
* Retention jobs for outbox, processed_events, saga_step_log.
* Alert on pool pending > 0 for 2 minutes, DB connections > 80% of max, p99 latency SLO burn.
* Migrations reviewed for lock level and table rewrites; `lock_timeout` set for DDL.

## Practice exercise

1. Local stack uses H2, so reproduce the application-side symptom, not the PostgreSQL views: set `spring.datasource.hikari.maximum-pool-size=2` for order-service and inject latency in the inventory validate call (a test stub with a 3 s sleep) while issuing 10 parallel order creations. Expect `hikaricp_connections_pending > 0` and timeouts in the order-service log. (Not executed: the services exist and run locally, but this latency-injection exercise was not run.)
2. For the PostgreSQL views, use a Testcontainers PostgreSQL test (extends `AbstractPostgresIT`; skipped without Docker, so run it where Docker exists): open a transaction updating one `stock` row, do not commit, start a second update, and query `pg_locks` / `pg_blocking_pids` from a third connection.
3. Write down which of the root-cause branches each experiment demonstrates.

## Regression test pointers

* Hikari saturation scenario (open): no test asserts that the inventory validate call is made before the transaction opens. Related, exists: `InventoryClientResilienceTest#aSlowInventoryHitsTheReadTimeoutAndIsRetried` (read timeout and retry) and `#theCircuitOpensAfterRepeatedFailuresAndRejectsCallsWithoutHittingTheServer`.
* Statement-count assertion for list endpoints (exists): `OrderRepositoryTest#searchingAPageOfOrdersWithItemsTakesAConstantNumberOfStatements`; background in `docs/code-review/06-unbounded-pagination-n-plus-one.md`.
* `AbstractPostgresIT` base class exists in `services/platform-test-support`.

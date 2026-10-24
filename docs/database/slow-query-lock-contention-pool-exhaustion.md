# Investigating slow queries, lock contention and connection-pool exhaustion (inventory-service)

Audience: the on-call engineer. The advice is generic PostgreSQL + HikariCP knowledge applied to this service's schema
and metrics. Outputs marked **captured** were produced on a real PostgreSQL 16.4 while building the service (see
`inventory-explain-analyze.md`, section 1, for how); everything else is a recipe, not evidence from an incident.

## 1. Symptoms and what they usually mean

| What you see | Most likely meaning |
|---|---|
| p99 of `http.server.requests` (or `oms.inventory.reservation.duration`) up, CPU of the DB low | waiting, not computing: **locks** or **pool** |
| log `HikariPool-... Connection is not available, request timed out after 3000ms` (here: `inventory-hikari`) | **pool exhausted**: every connection is busy or stuck |
| Kafka consumer lag of `inventory-service` grows (`GET /api/v1/ops/messaging`), `oms.events.retries` rising | consumer transactions are slow/failing: usually the same root cause as above |
| `oms.inventory.reservation.conflicts` rate rising | optimistic strategy / stock edits losing races on hot rows (**contention**, not a bug) |
| `CannotAcquireLockException`, `Deadlock detected` in logs | lock-order violation or lock timeout (see section 6) |
| CPU of the DB high, one statement dominates `pg_stat_statements` | **slow query**: a missing index or a plan change |
| `oms_outbox_pending` grows, Kafka healthy | relay blocked behind DB trouble or a poison row (section 7) |

## 2. Start with the application metrics (they tell you which of the three it is)

Service metrics (Prometheus names; the `service="inventory-service"` tag is global):

| Metric | Use |
|---|---|
| `hikaricp_connections_active{pool="inventory-hikari"}`, `_idle`, `_max`, `_pending` | `active == max` and `pending > 0` = exhausted |
| `hikaricp_connections_timeout_total` | counter of failed acquisitions (user-visible failures) |
| `hikaricp_connections_acquire_seconds` | how long threads wait for a connection |
| `hikaricp_connections_usage_seconds` | how long a connection is held per borrow: **long = slow/blocked transactions** |
| `http_server_requests_seconds_bucket` | `histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket{service="inventory-service"}[5m])))` |
| `oms_inventory_reservation_duration_seconds_bucket{strategy,outcome}` | reservation latency per strategy |
| `oms_inventory_reservations_total{outcome}` | rate of reserved / failed / duplicate / released / expired |
| `oms_inventory_reservation_conflicts_total` | lost optimistic races |
| `oms_events_retries_total`, `oms_events_dead_lettered_total`, `oms_outbox_pending` | consumer / relay health |

Decision rule that separates the cases:

```
pool saturated (active == max, pending > 0)?
 |-- no  -> the database is not the bottleneck of this service; look at Kafka lag and downstream.
 `-- yes -> usage_seconds high?
      |-- yes -> connections are HELD long: blocked on locks or running slow statements  (sections 3, 4, 5)
      `-- no  -> short transactions but too many of them: raise throughput headroom, check pool size vs. load (section 8)
```

Never "fix" a saturated pool by only increasing `OMS_DB_POOL_SIZE`: if the transactions are queued on one row lock, more
connections just add more waiters (and consume PostgreSQL backends). Find out why connections are held.

## 3. PostgreSQL: who is doing what (`pg_stat_activity`)

```sql
-- sessions of this service, longest transaction first
SELECT pid, application_name, state, wait_event_type, wait_event,
       now() - xact_start AS xact_age, now() - query_start AS query_age,
       pg_blocking_pids(pid) AS blocked_by, left(query, 100) AS query
FROM pg_stat_activity
WHERE datname = 'inventory_db' AND pid <> pg_backend_pid()
ORDER BY xact_start NULLS LAST;
```

How to read it:

* `state = 'active'` with `wait_event_type = 'Lock'` = waiting for a lock; `blocked_by` names the blocker.
* `state = 'idle in transaction'` = a client opened a transaction and is not sending statements. In this service no
  transaction calls out to other systems, so a long idle-in-transaction session means a stuck/paused client thread (GC,
  debugger, or a bug). It keeps its row locks. Protect yourself with `idle_in_transaction_session_timeout`.
* `wait_event_type = 'Client'` / `ClientRead` on `active` rows = the database is waiting for the app, not the reverse.
* Count sessions per state to compare with the pool: `SELECT state, count(*) FROM pg_stat_activity WHERE datname='inventory_db' GROUP BY 1;`
  (should be <= pool size x instances).

Set these in PostgreSQL so problems announce themselves (values are starting points):

```
log_lock_waits = on                 # logs a waiter after deadlock_timeout
deadlock_timeout = 1s
lock_timeout = 5s                   # per role/db for the app user: fail fast instead of queueing forever
statement_timeout = 15s             # app user only, never for migrations
idle_in_transaction_session_timeout = 30s
log_min_duration_statement = 250ms  # or use auto_explain
```

## 4. Lock contention: `pg_locks` and `pg_blocking_pids`

The reservation write path takes row locks in a fixed order: the reservation slot (unique `order_id`), then `stock` rows in
ascending `product_id`. Contention therefore shows up as a queue on ONE hot `stock` row (popular SKU) or on one unique
key (duplicate commands for the same order block on the unique index until the first one commits).

**Captured** reproduction (one session holds an uncommitted `UPDATE stock ...`, a second one tries the same row), observed
from a third session:

```
>> who is blocked by whom (pg_stat_activity + pg_blocking_pids)
SELECT a.pid, a.application_name, a.state, a.wait_event_type, a.wait_event, pg_blocking_pids(a.pid) AS blocked_by, now() - a.xact_start AS xact_age, left(a.query, 70) AS query FROM pg_stat_activity a WHERE a.application_name LIKE 'inventory-%' ORDER BY a.application_name
pid | application_name | state | wait_event_type | wait_event | blocked_by | xact_age | query
39996 | inventory-holder | idle in transaction | Client | ClientRead | {} | 00:00:01.518744 | UPDATE stock SET reserved = reserved + 1 WHERE product_id = md5('p100'
32704 | inventory-waiter | active | Lock | transactionid | {39996} | 00:00:01.490245 | UPDATE stock SET reserved = reserved + 1 WHERE product_id = md5('p100'

>> locks on the stock table (pg_locks)
SELECT l.pid, a.application_name, l.locktype, l.mode, l.granted, CASE WHEN l.locktype = 'relation' THEN l.relation::regclass::text ELSE l.page || ',' || l.tuple END AS target FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid WHERE a.application_name LIKE 'inventory-%' AND (l.locktype IN ('tuple','transactionid') OR l.relation = 'stock'::regclass) ORDER BY a.application_name, l.locktype, l.mode
pid | application_name | locktype | mode | granted | target
39996 | inventory-holder | relation | RowExclusiveLock | t | stock
39996 | inventory-holder | transactionid | ExclusiveLock | t | null
32704 | inventory-waiter | relation | RowExclusiveLock | t | stock
32704 | inventory-waiter | transactionid | ExclusiveLock | t | null
32704 | inventory-waiter | transactionid | ShareLock | f | null
32704 | inventory-waiter | tuple | ExclusiveLock | t | 0,100

>> blocking tree: waiter, blocker and the blocker's statement
SELECT w.pid AS waiting_pid, w.application_name AS waiting_app, h.pid AS blocking_pid, h.application_name AS blocking_app, h.state AS blocking_state, now() - h.xact_start AS blocking_xact_age, left(h.query, 60) AS blocking_last_query FROM pg_stat_activity w JOIN LATERAL unnest(pg_blocking_pids(w.pid)) b(pid) ON TRUE JOIN pg_stat_activity h ON h.pid = b.pid WHERE w.application_name LIKE 'inventory-%'
waiting_pid | waiting_app | blocking_pid | blocking_app | blocking_state | blocking_xact_age | blocking_last_query
32704 | inventory-waiter | 39996 | inventory-holder | idle in transaction | 00:00:01.548452 | UPDATE stock SET reserved = reserved + 1 WHERE product_id = 

Exception in thread "main" org.postgresql.util.PSQLException: Cannot rollback when autoCommit is enabled.
	at org.postgresql.jdbc.PgConnection.rollback(PgConnection.java:1036)
	at LockDemo.main(LockDemo.java:63)
```

Reading the capture: the waiter is `active`, `wait_event_type = Lock`, `wait_event = transactionid` (it waits for the
holder's transaction to finish) and `blocked_by = {39996}`; the holder is `idle in transaction`: its last statement was the
UPDATE and it is doing nothing. The waiter also owns the `tuple` lock of the row (queue head) and a non-granted
`ShareLock` on the holder's transaction id.

Useful follow-ups:

```sql
-- the blocking tree in one query (as used in the capture above)
SELECT w.pid AS waiting_pid, h.pid AS blocking_pid, h.state, now() - h.xact_start AS blocking_xact_age, left(h.query, 80) AS blocking_query
FROM pg_stat_activity w
JOIN LATERAL unnest(pg_blocking_pids(w.pid)) b(pid) ON TRUE
JOIN pg_stat_activity h ON h.pid = b.pid;

-- which table/row are we fighting for? (tuple locks show page,tuple)
SELECT l.pid, l.locktype, l.mode, l.granted, l.relation::regclass, l.page, l.tuple
FROM pg_locks l WHERE NOT l.granted OR l.locktype = 'tuple';

-- deadlock history (cumulative since stats reset)
SELECT datname, deadlocks, conflicts, temp_files FROM pg_stat_database WHERE datname = 'inventory_db';
```

Acting on it (least to most invasive): wait (lock waits that resolve in seconds are normal on a hot SKU) -> lower
consumer concurrency / pause the producer -> `SELECT pg_cancel_backend(<blocking pid>)` (cancels the statement, the
transaction stays) -> `pg_terminate_backend(<pid>)` (kills the session and rolls its transaction back: safe for this
service, because every effect is transactional and commands are idempotent, but it fails the in-flight command and Kafka
redelivers it). Always cancel the **blocker**, not the waiters.

## 5. Slow queries: `pg_stat_statements`

Enable once (`shared_preload_libraries = 'pg_stat_statements'`, restart, `CREATE EXTENSION pg_stat_statements;`). Then:

```sql
SELECT calls, round(total_exec_time::numeric,1) AS total_ms, round(mean_exec_time::numeric,3) AS mean_ms,
       round(max_exec_time::numeric,1) AS max_ms, rows, left(regexp_replace(query, '\s+', ' ', 'g'), 120) AS query
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = 'inventory_db')
ORDER BY total_exec_time DESC LIMIT 15;     -- also try ORDER BY mean_exec_time DESC and ORDER BY calls DESC
SELECT pg_stat_statements_reset();           -- before a controlled experiment
```

**Captured** output after running the PostgreSQL scenario suite (50 threads competing for one product; first row = the
optimistic compare-and-set, second = `FOR NO KEY UPDATE`, third = the atomic UPDATE):

```
>> SELECT calls, round(total_exec_time::numeric, 1) AS total_ms, round(mean_exec_time::numeric, 3) AS mean_ms, round(max_exec_time::numeric, 1) AS max_ms, rows, left(regexp_replace(query, '\s+', ' ', 'g'), 110) AS query FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = 'inventory_pg') AND query NOT LIKE '%pg_stat%' ORDER BY total_exec_time DESC LIMIT 12;
131 | 7796.3 | 59.513 | 353.2 | 93 | update stock s1_0 set reserved=(s1_0.reserved+$1),version=(s1_0.version+$6),updated_at=$2 where s1_0.product_i
132 | 3739.1 | 28.327 | 187.4 | 132 | select s1_0.product_id,s1_0.on_hand,s1_0.reserved,s1_0.updated_at,s1_0.version from stock s1_0 where s1_0.prod
132 | 2749.3 | 20.828 | 262.4 | 92 | update stock s1_0 set reserved=(s1_0.reserved+$1),version=(s1_0.version+$5),updated_at=$2 where s1_0.product_i
397 | 101.8 | 0.256 | 6.4 | 397 | insert into inventory_reservation_items (product_id,quantity,reservation_id,id) values ($1,$2,$3,$4)
281 | 75.9 | 0.270 | 4.1 | 281 | insert into stock_movements (actor,occurred_at,on_hand_after,on_hand_delta,product_id,reason,reference,reserve
278 | 59.9 | 0.215 | 4.7 | 278 | INSERT INTO inventory_reservations (id, order_id, status, expires_at, created_at, updated_at, version) VALUES 
334 | 49.1 | 0.147 | 1.3 | 334 | insert into outbox_events (aggregate_id,aggregate_type,attempts,created_at,event_key,event_type,headers,last_e
284 | 36.7 | 0.129 | 1.4 | 284 | insert into audit_events (action,actor,correlation_id,detail,entity_id,entity_type,occurred_at,id) values ($1,
1 | 29.6 | 29.595 | 29.6 | 1 | select string_agg(word, $1) from pg_catalog.pg_get_keywords() where word <> ALL ($2::text[])
120 | 20.2 | 0.169 | 0.6 | 120 | update inventory_reservations r1_0 set status=$5,expires_at=$6,failure_reason=$1,failure_detail=$2,updated_at=
18 | 10.6 | 0.590 | 7.3 | 18 | INSERT INTO stock (product_id, on_hand, reserved, version, updated_at) VALUES ($1, $2, $4, $5, $3)
344 | 10.6 | 0.031 | 1.0 | 63 | select r1_0.id,r1_0.created_at,r1_0.expires_at,r1_0.failure_detail,r1_0.failure_reason,r1_0.order_id,r1_0.rele

```

The lesson of this capture: `mean_ms` of 20-60 ms for statements that execute in microseconds is **lock wait**, which
`pg_stat_statements` charges to `total_exec_time`. A statement that is slow only under concurrency and fast alone (check
with `EXPLAIN (ANALYZE, BUFFERS)` on a quiet system) is a contention problem, not an indexing problem. A statement slow
even alone is a plan problem: compare with the captured plans in `inventory-explain-analyze.md` (the expiry scan without
its index took 501 ms vs 0.22 ms, 4 152 vs 5 buffers).

Checklist for a slow statement:

1. `EXPLAIN (ANALYZE, BUFFERS)` on a copy / with `BEGIN ... ROLLBACK` for DML; look for `Seq Scan` on big tables,
   `Rows Removed by Filter` far above rows returned, `Sort Method: external merge`, and estimate vs actual row counts
   off by 10x (stale statistics: `ANALYZE`).
2. `pg_stat_user_tables`: `seq_scan` vs `idx_scan`, `n_dead_tup` (bloat: autovacuum on `stock`, `inventory_reservations`,
   `outbox_events` matters because they are update/delete heavy), `last_autovacuum`.
3. `pg_stat_user_indexes`: unused (`idx_scan = 0`) or missing indexes.
4. Plan flips after a bulk change: `ANALYZE`, then look again.

## 6. Deadlocks

PostgreSQL log (needs no setup): `ERROR: deadlock detected / DETAIL: Process A waits for ShareLock on transaction ...`.
In this service a deadlock means that someone locked rows **out of order**: the contract is "ascending `product_id`,
within one transaction", enforced by `ReservationService.normalize`. Evidence that the rule is load-bearing: with the
sorting temporarily removed, the opposite-item-order test deadlocked on all three strategies (see
`docs/tdd/inventory-reservation-tdd.md`). Check any new code path that updates several `stock` rows (batch tools, repair
scripts) for the same ordering, and that nothing locks `stock` before the reservation row.

## 7. Outbox / consumer symptoms that look like DB problems

* `oms_outbox_pending` growing while Kafka is healthy: check `outbox_events` index use (`ix_outbox_pending`), long
  transactions holding the relay's connection, and `attempts`/`last_error` columns of the oldest unpublished row.
* Consumer lag growing with `oms_events_retries_total`: the handler throws; the log line has the exception, the DLT
  record carries `x-exception-class` / `x-exception-message`. `ReservationContentionException` = optimistic retries
  exhausted on a hot row (switch strategy or reduce concurrency, section 9).
* A duplicate flood (`outcome="duplicate"` high) is cheap by design (read + re-emit), but each duplicate still opens a
  transaction; if it is a saga retry storm, fix the sender (`oms.saga.stuck-after-seconds`).

## 8. Connection pool: sizing and settings

Settings used by the service (`application.yml`, env override in brackets):

| Setting | Value | Why |
|---|---|---|
| `maximum-pool-size` [`OMS_DB_POOL_SIZE`] | 20 | reservations hold a connection for ONE short transaction; the DB saturates long before 20 |
| `minimum-idle` [`OMS_DB_POOL_MIN_IDLE`] | 5 | warm connections for bursts |
| `connection-timeout` [`OMS_DB_CONNECTION_TIMEOUT_MS`] | 3000 ms | fail fast; callers retry (saga, Kafka backoff) instead of piling up request threads |
| `max-lifetime` | 25 min | below any server/pgbouncer/firewall idle limit so the pool recycles connections itself |
| `leak-detection-threshold` [`OMS_DB_LEAK_DETECTION_MS`] | 0 (off) | set 10000 while hunting a leak: logs the stack trace of the borrower |

Sizing logic (Little's law): connections needed = throughput x average hold time. 400 reservations/s x 10 ms = 4
connections busy on average; the pool of 20 is headroom for bursts, not a target. Hold time is dominated by lock waits
on hot rows, so the lever is contention (section 9), not more connections. Across instances:
`instances x pool size + other services' connections + admin < max_connections` (leave headroom; use PgBouncer in
transaction mode if many instances). Kafka listener threads x pool: the three listeners use one thread each by default
(`oms.messaging.concurrency`), plus request threads, the outbox relay and the expiry job; all share one pool.

Pool metrics worth alerting on: `hikaricp_connections_pending > 0` for 1 min, `hikaricp_connections_timeout_total`
rate > 0, p99 `hikaricp_connections_usage_seconds` > 1 s.

## 9. Remedies by cause

| Cause | Remedy |
|---|---|
| hot SKU (one row everybody reserves) | accept the queue (it is correct); keep transactions short; stay on `atomic` (never `optimistic` for hot rows); reduce per-reservation writes if needed; long term: split stock into N sub-rows ("buckets") reserved at random |
| optimistic retry storm (`conflicts` rate high) | `OMS_RESERVATION_STRATEGY=atomic` and restart (rolling); optimistic is only good with spread-out rows |
| duplicate/retry flood on the same order | fix the sender; duplicates serialize on the unique index of that order by design |
| long / idle-in-transaction sessions | find the owner via `pg_stat_activity`, fix the client; set `idle_in_transaction_session_timeout` |
| missing or lost index, stale stats | add the index (`CREATE INDEX CONCURRENTLY`), `ANALYZE`; compare with the plans in `inventory-explain-analyze.md` |
| bloat on update-heavy tables | autovacuum tuning on `stock`, `inventory_reservations`, `outbox_events` (lower `autovacuum_vacuum_scale_factor`) |
| pool too small for steady load | raise `OMS_DB_POOL_SIZE` only after the checks above, and re-check `max_connections` |
| catalog reads hurting reservations | enable the product-read cache (`OMS_REDIS_ENABLED=true`, see README staleness notes) and/or route `GET /products` to a read replica |

## 10. Quick runbook

1. Dashboard: p99 up? pool saturated? (section 2).
2. `pg_stat_activity` + `pg_blocking_pids` (sections 3, 4): is there a blocker? who owns it?
3. `pg_stat_statements` ordered by total and by mean time (section 5): one statement or many?
4. Decide: contention (strategy, concurrency, blocker) or plan problem (index, stats, bloat).
5. Mitigate (cancel blocker, reduce consumer concurrency, rolling restart with another strategy), then fix the cause and
   add the missing alert from section 8.

## What was and was not verified

* **Captured on a real PostgreSQL 16.4:** the lock-wait reproduction (`pg_stat_activity`, `pg_locks`,
  `pg_blocking_pids`), the `pg_stat_statements` ranking, the expiry-scan plan with and without its index.
* **Verified in the test-suite:** the sorted lock order prevents deadlocks (mutation-checked), Hikari pool name and
  timeout come from the configuration, the Prometheus metrics named above exist.
* **Not captured / not verified:** `auto_explain` output, PgBouncer behaviour, Hikari pool metrics values from a live
  saturated pool (names are the standard Micrometer ones), behaviour under a real production workload.

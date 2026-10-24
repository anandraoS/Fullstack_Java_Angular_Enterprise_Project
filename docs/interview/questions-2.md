# Interview questions Q49-Q96

Scope of this file: PostgreSQL, Angular and TypeScript, RxJS and Signals, React/Redux mapping, Spring Security and OAuth, Docker and Kubernetes, and AWS deployment. All answers are tied to the Order Management and Fulfillment demo in this repository (Java 17, Spring Boot 3.5.16, Angular 20, PostgreSQL 16). Honesty rules used throughout: the JWT setup is HS256 and demonstration-only; the Kubernetes manifests and Terraform were statically validated but never applied to a cluster or an AWS account; Docker is not installed on the author machine, so images are built only by the CI `docker` job (defined in `.github/workflows/ci.yml`, not executed locally); the UI contains no React code; plan captures quoted from `docs/database/inventory-explain-analyze.md` come from one laptop with synthetic data and are not capacity figures.

## PostgreSQL and query optimization

### Q49. Scenario: Optimize a slow PostgreSQL query
**Type:** Scenario   **Level:** Advanced   **Topic:** EXPLAIN ANALYZE, composite indexes

**Short answer.** Reproduce the statement, run `EXPLAIN (ANALYZE, BUFFERS)`, find the node that burns time or buffers (here a parallel sequential scan with `Rows Removed by Filter: 99900`), then add the narrowest index that matches the predicate and the sort order and re-measure. In this repo the inventory expiry scan went from 501 ms and 4,152 buffers to 0.22 ms and 5 buffers with the composite index `ix_reservations_status_expiry (status, expires_at)`.

**Detailed answer.** The statement is the scheduler's expiry query: `SELECT id FROM inventory_reservations WHERE status = 'RESERVED' AND expires_at < now() ORDER BY expires_at ASC LIMIT 100`. The captured data set (`docs/database/inventory-explain-analyze.md`, section 3.2) had 300,000 reservations of which 300 were overdue.

Method I would narrate:
- Confirm it is a plan problem, not a lock problem: run the statement alone on a quiet system. If it is fast alone and slow only under load, it is contention (see Q53).
- Read the plan bottom-up. Without the index PostgreSQL 16.4 chose `Gather` over `Parallel Seq Scan`, `Filter: status = 'RESERVED' AND expires_at < now()`, `Rows Removed by Filter: 99900` per worker, `Buffers: shared hit=3 read=4152`, then a top-N heapsort. With the index: `Index Scan using ix_reservations_status_expiry`, `Index Cond` containing both predicates, `Buffers: shared read=5`, and no Sort node because the index already returns rows ordered by `expires_at` within `status`.
- Design the index: equality column first (`status`), range/sort column second (`expires_at`). An index on `expires_at` alone would also have to filter on status.
- Re-run, compare buffers (hardware-independent) as well as milliseconds.

```
Index Scan using ix_reservations_status_expiry on inventory_reservations
  Index Cond: (((status)::text = 'RESERVED'::text) AND (expires_at < now()))
  Buffers: shared read=5
Execution Time: 0.223 ms
```

The capture dropped the index inside a rolled-back transaction (`DROP INDEX` is transactional in PostgreSQL), so the data set stayed unchanged. Do not quote these as capacity numbers: one laptop, synthetic data, `shared_buffers` 128 MB. No production plans exist in this repo (section 6 of the doc is an unfilled template).

**Trade-offs and failure modes.**
- Every index slows writes and consumes space; `inventory_reservations` is write-heavy.
- A stale plan after bulk loads needs `ANALYZE`; estimate-versus-actual drift of 10x is the signal.
- Skewed production data can flip plans; verify on a restored copy, not on the primary.
- On a live table create indexes with `CREATE INDEX CONCURRENTLY` (see Q56).

**Likely follow-ups.**
- Why not `expires_at` alone? The status equality filter would be applied after the index scan; the composite makes both predicates index conditions.
- Why buffers? They are stable across machines and caches, unlike milliseconds.
- Would you use a partial index? Possibly `WHERE status = 'RESERVED'`; I would measure against the composite first.

### Q50. How do you read EXPLAIN (ANALYZE, BUFFERS) output?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** Query plans

**Short answer.** `EXPLAIN` shows the planner's estimate; `ANALYZE` actually executes and adds real times and row counts; `BUFFERS` adds page counts. You compare estimated and actual rows, look at the node types, `Rows Removed by Filter`, loops, sort method, and shared hit versus read.

**Detailed answer.** Use the catalog page query captured in `docs/database/inventory-explain-analyze.md` (section 3.3) as the teaching example.

Reading rules:
- Plans are trees; the innermost, most indented nodes run first. Actual time is per loop, so multiply by `loops` (the `stock_pkey` scan shows `loops=21`).
- `rows=490` estimated versus `rows=21` actual under a `Limit` is normal: the limit stops the scan early.
- `Rows Removed by Filter: 786` against `rows=21` returned shows the planner walked `uq_products_sku` in sort order and filtered, rather than using `ix_products_category`, because `LIMIT 20` makes an ordered walk cheaper.
- `Incremental Sort` with `Presorted Key: p.sku` means only the tie-break `id` was sorted.
- `Buffers: shared hit` came from cache, `read` came from the OS or disk. First runs show reads, repeats show hits, so run twice.
- `Planning Time` can exceed execution for tiny statements (5.9 ms planning versus 1.2 ms execution here), which matters for statements executed thousands of times; use prepared statements.
- `EXPLAIN ANALYZE` of `UPDATE`/`DELETE` really modifies data, so wrap in `BEGIN ... ROLLBACK` (the repo's captures did).

Snippet from the repo template:

```sql
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
UPDATE stock SET reserved = reserved + 1, version = version + 1, updated_at = now()
 WHERE product_id = '<real product uuid>' AND on_hand - reserved >= 1;
ROLLBACK;
```

The doc also shows H2's `EXPLAIN ANALYZE`, which prints the chosen index and `scanCount` but no costs or timings: use it to confirm the same indexes are used, never for performance conclusions.

**Trade-offs and failure modes.**
- `ANALYZE` timing overhead can distort very fast nodes.
- A plan on a laptop with a warm cache is not representative of production.
- Parameter-dependent plans: generic versus custom plans for prepared statements can differ; check with realistic parameter values.

**Likely follow-ups.**
- What is the first red flag? A `Seq Scan` on a large table with a large `Rows Removed by Filter`, or estimates off by 10x.
- When do you run `ANALYZE`? After bulk changes or when estimates drift; autovacuum normally handles it.
- How do you capture slow plans automatically? `auto_explain` with `log_min_duration` (recommended in the doc, not captured here).

### Q51. How did you choose and order the indexes on the orders table?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** Composite index design

**Short answer.** The order list is always newest first with optional customer and status filters, so each index starts with the equality columns and ends with `created_at`. A backward index scan then serves `ORDER BY created_at DESC` without a separate sort.

**Detailed answer.** The indexes live in `services/order-service/src/main/resources/db/migration/V12__indexes.sql`:

```sql
CREATE INDEX ix_orders_customer_created        ON orders (customer_id, created_at);
CREATE INDEX ix_orders_customer_status_created ON orders (customer_id, status, created_at);
CREATE INDEX ix_orders_status_created          ON orders (status, created_at);
CREATE INDEX ix_orders_created                 ON orders (created_at);
```

Reasoning:
- Customers can only list their own orders (the filter is forced from the token), so `(customer_id, created_at)` is the hot path for that role. Adding `status` in the middle serves "my CREATED orders" while keeping the ordering.
- Staff screens filter by `status` across all customers: `(status, created_at)`.
- With no filter, `ix_orders_created` gives the newest page directly.
- The file comment explains plain ascending indexes are enough because PostgreSQL scans a b-tree backward; this also keeps the SQL portable to H2 in `MODE=PostgreSQL`, which is a real constraint of this repo (migrations must run unchanged on both).
- The leftmost-prefix rule: `(customer_id, status, created_at)` also serves `customer_id` alone, which makes `ix_orders_customer_created` partly redundant; I would check `pg_stat_user_indexes.idx_scan` in production and drop unused ones.
- Search `q` uses `lower(...) LIKE` on email/city prefix (`OrderSpecifications`); a plain b-tree on `lower(...)` is not present, so those predicates are filters, not index conditions. I cannot claim a measured result for the orders table: only the inventory plans were captured.

**Trade-offs and failure modes.**
- Redundant indexes cost write throughput and storage.
- Deep `OFFSET` pagination still walks skipped rows; keyset pagination is the fix if pages get deep (not implemented; the contract uses `page`/`size`).
- Low-selectivity columns like `status` alone are weak; they only work combined with `created_at` and a `LIMIT`.

**Likely follow-ups.**
- Why not DESC in the index? A backward scan makes it unnecessary for single-direction sorts.
- When would you add a partial index? When a rare status is queried repeatedly, for example the failed-orders view.
- How would you prove an index is used? `EXPLAIN` plus `pg_stat_user_indexes`.

### Q52. Scenario: Product search with LIKE '%gloves%' is slow. Would you add a trigram index?
**Type:** Scenario   **Level:** Intermediate   **Topic:** pg_trgm, sequential scans

**Short answer.** A b-tree cannot serve a leading-wildcard `LIKE`, so the planner scanned `products` sequentially (40 ms for 20,000 rows). In the repo experiment the GIN trigram indexes were created but not used; the improvement to 4.3 ms came from better statistics on the indexed expressions, so the honest answer is: measure first, and do not add trigram indexes pre-emptively.

**Detailed answer.** The captured query filters `lower(p.name) LIKE '%gloves%' ESCAPE '\' OR lower(p.sku) LIKE '%gloves%'` with `ORDER BY p.sku, p.id LIMIT 20` (`docs/database/inventory-explain-analyze.md`, section 3.3).

What the evidence shows:
- Before: `Seq Scan on products`, `Rows Removed by Filter: 19798`, estimate `rows=13` versus `actual rows=202` (a 15x misestimate), 40.4 ms.
- After creating `pg_trgm` GIN indexes (inside a rolled-back transaction) and `ANALYZE`: the plan walked `uq_products_sku` and filtered, estimate `rows=200`, 4.3 ms. Trigram indexes were not chosen.
- Why: about 1 percent of rows match and `LIMIT 20` lets an ordered walk stop after about 2,000 rows. Better row estimates changed the plan shape.

What I would do in an interview:
1. State that the sequential scan is O(n) and fine at 20,000 rows.
2. Check whether the product catalog will grow by orders of magnitude; if so, test `CREATE INDEX ... USING gin (lower(name) gin_trgm_ops)` on a restored copy.
3. If search quality matters (stemming, ranking), use PostgreSQL full-text search (`tsvector`) or an external search engine rather than `LIKE`.
4. Prefer prefix search (`LIKE 'glo%'`) with `text_pattern_ops` or a collation-aware index when product requirements allow it.

Another honest caveat: `pg_trgm` is an extension, and migrations must stay portable to H2; an extension index would have to be PostgreSQL-only and guarded, which conflicts with the "same Flyway SQL on both engines" rule in `docs/architecture/00-shared-contract.md`.

**Trade-offs and failure modes.**
- GIN indexes are large and slow to update.
- Trigram search needs at least three characters in the pattern to be selective.
- Planner may ignore the index for non-selective patterns (as observed).

**Likely follow-ups.**
- Why did execution drop 10x without the index? Statistics on the expressions improved the estimate, changing the join and scan order.
- Would you cache results? The optional Redis cache only caches `GET /products/{id}`, not search.
- How do you test an index safely? In a transaction you roll back, or on a copy.

### Q53. Scenario: p99 latency spikes on inventory reservations while the database CPU is low. How do you investigate?
**Type:** Scenario   **Level:** Advanced   **Topic:** Locks, pg_stat_activity, pg_locks

**Short answer.** Low database CPU with high latency means waiting, not computing: row locks or connection-pool saturation. I check Hikari metrics first, then `pg_stat_activity` with `pg_blocking_pids`, then `pg_stat_statements` to see whether the cheap statements show large mean times (lock wait).

**Detailed answer.** The runbook is `docs/database/slow-query-lock-contention-pool-exhaustion.md`, with captured output from PostgreSQL 16.4.

Steps:
1. Metrics: `hikaricp_connections_active == max` and `_pending > 0` means pool exhaustion; high `hikaricp_connections_usage_seconds` means connections are held long.
2. Blockers:

```sql
SELECT w.pid AS waiting_pid, h.pid AS blocking_pid, h.state,
       now() - h.xact_start AS blocking_xact_age, left(h.query, 80) AS blocking_query
FROM pg_stat_activity w
JOIN LATERAL unnest(pg_blocking_pids(w.pid)) b(pid) ON TRUE
JOIN pg_stat_activity h ON h.pid = b.pid;
```

The captured reproduction showed the waiter `active` with `wait_event_type = Lock`, `wait_event = transactionid`, `blocked_by = {39996}`, and the holder `idle in transaction`.
3. `pg_stat_statements`: the same primary-key `UPDATE stock` averaged 21 ms (atomic), 28 ms (`FOR NO KEY UPDATE`) and 59 ms (optimistic) under 50 competing threads, although it executes in well under a millisecond alone. That gap is lock wait, which `total_exec_time` includes.
4. Decide: contention (hot SKU) versus plan problem. A statement slow only under concurrency is contention.

Actions from least to most invasive: wait (hot-SKU queues are correct), reduce consumer concurrency, `pg_cancel_backend` on the blocker, then `pg_terminate_backend`. Cancel the blocker, never the waiters. It is safe here because every effect is transactional and commands are idempotent; Kafka redelivers the interrupted command.

Prevention: lock order is fixed (reservation row, then `stock` rows by ascending `product_id`, `ReservationService.normalize`), and the doc records that removing the sort made the opposite-order test deadlock on all three strategies. Set `lock_timeout`, `idle_in_transaction_session_timeout`, and `log_lock_waits` for the application role.

**Trade-offs and failure modes.**
- Terminating a backend fails the in-flight command; fine only because of idempotency.
- Raising pool size only adds more waiters and consumes PostgreSQL backends.
- `lock_timeout` turns indefinite queues into errors that callers must retry.

**Likely follow-ups.**
- What does `idle in transaction` imply? A client opened a transaction and stopped sending statements, holding its locks.
- How do you split a hot SKU? Stock "buckets" reserved at random (documented as long-term, not implemented).
- Which strategy for hot rows? `atomic` or `pessimistic`; never `optimistic`.

### Q54. Atomic conditional UPDATE, pessimistic locking, or optimistic locking for stock reservation?
**Type:** Conceptual   **Level:** Advanced   **Topic:** Concurrency control

**Short answer.** The default is a single conditional `UPDATE ... WHERE on_hand - reserved >= :q`; zero affected rows means insufficient stock. PostgreSQL's READ COMMITTED behaviour re-evaluates the predicate against the latest committed row version after waiting for the lock, so no read-modify-write window exists. Pessimistic and optimistic strategies are implemented for comparison.

**Detailed answer.** The strategy is configured by `oms.inventory.reservation-strategy` (`atomic` default, `pessimistic`, `optimistic`; env `OMS_RESERVATION_STRATEGY`). Code: `StockRepository.tryReserve`, `PessimisticLockReservationStrategy`, and the benchmark in `docs/database/inventory-explain-analyze.md` section 4.

```
Update on stock ... 
  ->  Index Scan using stock_pkey on stock
        Index Cond: (product_id = '...'::uuid)
        Filter: ((on_hand - reserved) >= 1)
```

Why the atomic form is correct: a concurrent writer holds the row lock; the second `UPDATE` waits, then PostgreSQL re-checks the `WHERE` clause on the newest committed version (the EvalPlanQual recheck), so two transactions cannot both consume the last unit. The database also enforces `ck_stock_non_negative` and `ck_stock_reserved_le_on_hand`, so an application bug cannot oversell silently.

Measured on one laptop (16 threads, 150 calls per cell): on a single hot row atomic managed 129 ops/s, pessimistic 164, optimistic 64 with 2,389 lost compare-and-sets; spread over 200 products all three were about 800 ops/s. Multi-line orders over 10 hot rows punished optimistic again. Takeaways: one row lock is a physical limit; optimistic is good only for spread rows (and for admin stock edits via `@Version`).

All-or-nothing: each reservation runs in one transaction, and rows are locked in ascending `product_id` to avoid deadlocks.

**Trade-offs and failure modes.**
- Atomic UPDATE hides the "why" of a failure; the follow-up SELECT builds shortages.
- Pessimistic locks can queue many request threads behind a long transaction.
- Optimistic retries exhaust (`ReservationContentionException`) on hot rows.
- Under `SERIALIZABLE` you would need retry on serialization failure (SQLSTATE 40001); not used here.

**Likely follow-ups.**
- Why not `SELECT` then `UPDATE`? A race between the read and the write.
- Does Hibernate emit `FOR UPDATE`? On PostgreSQL `PESSIMISTIC_WRITE` appeared as `for no key update` in the capture.
- Are the benchmark numbers a capacity statement? No.

### Q55. Scenario: Hikari reports "Connection is not available, request timed out after 3000ms". What do you do?
**Type:** Scenario   **Level:** Advanced   **Topic:** Connection pool sizing

**Short answer.** The pool is exhausted. Decide whether connections are held too long (blocked on locks or slow statements) or whether there are simply too many short transactions. Do not only raise `OMS_DB_POOL_SIZE`; more connections behind one row lock just add waiters.

**Detailed answer.** The decision tree is in `docs/database/slow-query-lock-contention-pool-exhaustion.md` (section 2):

```
pool saturated (active == max, pending > 0)?
 |-- no  -> the database is not this service's bottleneck
 `-- yes -> usage_seconds high?
      |-- yes -> connections HELD long (locks, slow statements)
      `-- no  -> short transactions, too many: check pool vs load
```

Settings in `services/inventory-service/src/main/resources/application.yml`: pool name `inventory-hikari`, `maximum-pool-size` 20 (env `OMS_DB_POOL_SIZE`), `minimum-idle` 5, `connection-timeout` 3000 ms (fail fast so callers retry through the saga or Kafka backoff), `max-lifetime` 25 minutes (below server or firewall idle limits), leak detection off (set about 10 s while hunting a leak).

Sizing by Little's law: connections needed equals throughput times hold time. The doc's example: 400 reservations/s times 10 ms is 4 busy connections on average; 20 is burst headroom. Across instances: `instances x pool size + other clients < max_connections`, with PgBouncer in transaction mode if many pods. Listener threads, request threads, the outbox relay, and the expiry job all share one pool.

Alert on `hikaricp_connections_pending > 0` for one minute, a nonzero rate of `hikaricp_connections_timeout_total`, and p99 `usage_seconds` above one second.

Note the doc is explicit about its limits: pool metric values from a live saturated pool and PgBouncer behaviour were not captured; metric names are the standard Micrometer ones.

**Trade-offs and failure modes.**
- Small timeout fails fast but surfaces errors to users sooner; the saga and Kafka backoff absorb it for asynchronous work.
- Large pools overwhelm PostgreSQL (each connection is a process).
- A transaction that calls another service while holding a connection multiplies hold time; this service avoids that by design.

**Likely follow-ups.**
- Where is a leak visible? `usage_seconds` climbing with `leak-detection-threshold` stack traces.
- Why `max-lifetime` below firewall limits? Avoid half-dead connections.
- What if the cause is a hot SKU? Remedy is contention strategy, not pool size.

### Q56. Scenario: Add an index and a new column to a live PostgreSQL table without downtime
**Type:** Scenario   **Level:** Advanced   **Topic:** Flyway, expand/contract

**Short answer.** Use expand and contract: ship a backward-compatible migration first, deploy code that works with both schemas, and clean up in a later release. Create indexes with `CREATE INDEX CONCURRENTLY`, which cannot run inside a transaction, and note the tension with this repo's portable-SQL rule.

**Detailed answer.** Repo rules (`docs/security/secure-coding-guide.md` section 2, runbook `docs/runbooks/07-failed-deployment.md`): next Flyway version in the service range (`V10+`; `V1..V8` belong to platform-common, `V1000+` is dev seed data), never edit an applied migration (checksum mismatch), portable SQL for PostgreSQL 16 and H2 `MODE=PostgreSQL`.

Plan:
1. Add the column nullable (or with a constant default; on PostgreSQL 11+ a constant default is a metadata-only change, while volatile defaults rewrite the table). Avoid `NOT NULL` without default on a populated table.
2. Release N: code writes both old and new representation, tolerant of null.
3. Backfill in batches (small transactions) to avoid long locks and replication lag.
4. Add the constraint as `NOT VALID`, then `VALIDATE CONSTRAINT`, which takes a weaker lock.
5. Index: `CREATE INDEX CONCURRENTLY ix_... ON t (...)`. It avoids blocking writes but runs two table scans and cannot execute in a transaction block. Flyway wraps migrations in a transaction by default, so you must configure that migration to run outside one (the `executeInTransaction` script setting; check your Flyway version's behaviour for PostgreSQL). It also is not an H2 syntax, so a concurrent index needs a PostgreSQL-only location or the portable plain `CREATE INDEX` accepted for small tables. This is a decision to record, not hide.
6. Release N+1 (contract): stop writing the old column; drop it in a later migration.

Rollback story from `infrastructure/kubernetes/README.md`: the previous version must run against the new schema, because `kubectl rollout undo` does not undo schema changes. A failed index build leaves an `INVALID` index that must be dropped.

**Trade-offs and failure modes.**
- Plain `CREATE INDEX` blocks writes for the build duration.
- Long migrations hold locks and can fail the readiness probe (runbook 04).
- `lock_timeout` on the migration role avoids queueing behind a long transaction while holding an `ACCESS EXCLUSIVE` request that blocks everyone.

**Likely follow-ups.**
- Can you roll back a Flyway migration? Not automatically (community edition has no undo); write a corrective migration.
- What if the migration fails half-way? Fix the cause, then `flyway repair` only with approval.
- Which lock does `ALTER TABLE ADD COLUMN` take? `ACCESS EXCLUSIVE`, but briefly when metadata-only.

### Q57. Why do update-heavy tables such as stock and outbox_events need autovacuum attention?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** MVCC, bloat, vacuum

**Short answer.** PostgreSQL implements updates as insert-new-version plus mark-old-dead (MVCC), so frequent updates create dead tuples that VACUUM must reclaim. Without timely vacuum, tables and indexes bloat, scans slow down, and statistics go stale.

**Detailed answer.** `docs/database/slow-query-lock-contention-pool-exhaustion.md` (section 5 checklist, section 9) names the update-heavy tables: `stock`, `inventory_reservations` and `outbox_events`. Every reservation updates a `stock` row (`reserved = reserved + q`, `version = version + 1`); the outbox relay updates `published_at` and `attempts`; the retention job deletes old published rows (`OutboxEventRepository.deletePublishedBefore`).

Mechanics:
- An `UPDATE` writes a new tuple version; readers with older snapshots can still see the old one, so it cannot be removed until no transaction needs it. Long-running or `idle in transaction` sessions hold back the cleanup horizon for the whole database.
- Autovacuum triggers when dead tuples exceed `autovacuum_vacuum_threshold + scale_factor * rows` (default scale factor 0.2). For a small hot table that is fine; for a large `outbox_events` it can mean millions of dead rows, so lower `autovacuum_vacuum_scale_factor` per table, as the doc suggests.
- Hot updates: PostgreSQL can do a HOT update (no new index entries) when no indexed column changes and the page has room. `stock` updates change `reserved`, `version`, `updated_at`; only the primary key is indexed, so most updates can be HOT, which keeps bloat down. `outbox_events` updates `published_at`, which is part of `ix_outbox_pending`, so those updates are not HOT and index bloat is real there.
- Diagnostics: `pg_stat_user_tables` (`n_dead_tup`, `last_autovacuum`, `seq_scan` versus `idx_scan`).

The doc's honesty line: `auto_explain` output and behaviour under real production workload were not captured.

**Trade-offs and failure modes.**
- Aggressive autovacuum consumes IO; tune cost limits.
- `VACUUM FULL` takes an exclusive lock and rewrites the table; avoid on live tables.
- Transaction ID wraparound is prevented by vacuum freezing; never disable autovacuum.

**Likely follow-ups.**
- Why delete published outbox rows? To bound table and index size; the scheduled retention job does it.
- How do you detect bloat? Dead tuple ratio plus size growth; `pgstattuple` for precision.
- Does an idle-in-transaction session affect vacuum? Yes, it pins the oldest snapshot.

## Angular and TypeScript

### Q58. How is the Angular 20 UI structured, and why standalone components with lazy routes?
**Type:** Conceptual   **Level:** Beginner   **Topic:** Standalone components, routing

**Short answer.** The app is NgModule-free: every screen is a standalone component loaded with `loadComponent`, so each feature becomes its own chunk and the initial bundle only carries bootstrap, login, and shared code. Core services are singletons; features depend on core and shared, never the reverse.

**Detailed answer.** From `docs/architecture/angular-architecture.md` and `frontend/oms-ui/src/app/app.routes.ts`:

```ts
{
  path: 'inventory',
  canActivate: [roleGuard],
  data: { roles: STAFF },
  title: 'Inventory',
  loadComponent: () => import('./features/inventory/inventory').then((m) => m.Inventory),
},
```

Layers: `core/` (config, auth store, interceptors, typed API clients in `core/api/apis.ts`, state stores), `features/` (one folder per screen), `shared/components/` (presentational: `data-table.ts`, `status-chip.ts`, `confirm-dialog.ts`), `testing/`.

Details worth mentioning:
- `withComponentInputBinding()` in `app.config.ts` binds route params to `input()`s: `OrderDetail` declares `readonly id = input.required<string>()`, no `ActivatedRoute` plumbing.
- Templates use the built-in control flow (`@if`, `@for ... track`, `@switch`) rather than `*ngIf`/`*ngFor`.
- Shared components take `input()`s and emit `output()`s and never call APIs, which keeps them testable.
- Route `title` is set per route; the wildcard route loads `NotFound`.
- Dependency injection uses `inject()` in field initialisers, not constructor parameters.

I would also say honestly what is not done: components do not use `ChangeDetectionStrategy.OnPush` (a grep of `src/app` finds none), and the app still runs with zone.js (`provideZoneChangeDetection({ eventCoalescing: true })`). Signals work with this setup and make a later move to OnPush or zoneless easier.

**Trade-offs and failure modes.**
- Many small chunks mean more requests on first navigation; preloading strategies can mitigate (not configured here).
- Without module boundaries, discipline (and lint rules) must enforce the dependency direction.
- Lazy chunks fail to load after a deployment replaces hashed files; users on a stale shell need a reload.

**Likely follow-ups.**
- What replaced NgModules for providers? `ApplicationConfig` and route-level `providers`; component-level `providers` as in `OrderDetail` (`providers: [OrderDetailStore]`).
- How do you test a routed component? Provide the router and set the input via the test harness; see `*.spec.ts` beside the code.
- Why `track` in `@for`? It lets Angular reuse DOM nodes; it is required.

### Q59. Scenario: Design the HTTP interceptor pipeline for auth, tracing, retries and errors
**Type:** Scenario   **Level:** Intermediate   **Topic:** HttpInterceptorFn

**Short answer.** Use functional interceptors registered with `provideHttpClient(withInterceptors([...]))`. Order matters: requests flow left to right, responses and errors right to left. In this repo the chain is correlation, auth, retry, error, so retry sees normalised errors and mutations are never retried.

**Detailed answer.** `frontend/oms-ui/src/app/app.config.ts` registers the chain; the implementations are in `core/http/interceptors.ts`.

- `correlationInterceptor` adds `X-Correlation-Id` (`crypto.randomUUID()`) to API calls so one click can be traced through gateway and services.
- `authInterceptor` adds `Authorization: Bearer` only to API URLs and never to `/auth/token` or foreign origins (`isApiCall`, `isTokenCall`). That prevents leaking the token to third-party hosts.
- `retryInterceptor` retries only `GET`/`HEAD`, twice, with `timer(200 * 2 ** (attempt - 1))`, and only for status 0, 502, 503, 504:

```ts
retry({
  count: MAX_GET_RETRIES,
  delay: (err, attempt) => (isTransient(err) ? timer(200 * 2 ** (attempt - 1)) : throwError(() => err)),
}),
```

- `errorInterceptor` normalises to `ApiError` (RFC 7807 `code`, `correlationId`, `errors[]`), logs out on 401 (unless the token call or already logged out), and toasts 429 with `Retry-After`.

Why this order: correlation must be set before anything can fail; auth must precede retry so retried requests carry the header; retry sits before the error interceptor on the request side but, on the response side, error normalisation happens first, so retry sees `ApiError` (status preserved).

Security points: tokens never go to non-API origins; 401 handling is centralised; the UI does not echo server `detail` for authentication failures.

The unsafe retry decision is deliberate: order creation relies on `Idempotency-Key`, handled in `IdempotencyKeyHolder` (Q62), rather than blind retries.

**Trade-offs and failure modes.**
- Retrying GETs multiplies load during an outage; the cap of two with backoff limits it, and jitter is not implemented.
- A 401 that arrives while many requests are in flight triggers repeated logout calls; guarded by `isAuthenticated()`.
- No token refresh exists: the demo token expires after 3,600 seconds and the session timer logs out.

**Likely follow-ups.**
- Class-based `HttpInterceptor` or functional? Functional is the current recommended style and supports `inject()`.
- How would you add token refresh? Single-flight refresh with a shared observable and `switchMap`, only with a real IdP.
- Where do you show the correlation id? In error toasts, so support can search logs.

### Q60. Signals, zone.js and OnPush: how does change detection work in this app, and what would zoneless need?
**Type:** Conceptual   **Level:** Advanced   **Topic:** Change detection

**Short answer.** The app is zone-based: `provideZoneChangeDetection({ eventCoalescing: true })` triggers change detection after async events. Signals read in templates mark the component for refresh and work with zones; they also prepare a move to OnPush or zoneless. Zoneless was developer preview in Angular 20.0 (check the release notes of the exact 20.x for stable status).

**Detailed answer.** `frontend/oms-ui/package.json` pins Angular 20.3.x, zone.js 0.15.1, RxJS 7.8.2, TypeScript 5.9.3.

How it works today:
- zone.js patches browser APIs; after any event, timer, or XHR completes, Angular runs change detection across the tree (default strategy). `eventCoalescing: true` merges multiple events in one tick.
- A signal read in a template registers a dependency; when it changes, the component's view is marked for check. With OnPush, this is how updates propagate without zone-triggered full-tree checks.
- Stores such as `AuthStore` (`computed(() => this._session()?.roles ?? [])`) and `CartStore` expose `asReadonly()` signals and `computed` derivations.

What would change for zoneless (`provideZonelessChangeDetection()`; developer preview in v20.0, announced stable later in the 20.x line, verify against the exact release notes):
- Remove `zone.js` from `angular.json` polyfills and tests.
- Anything that mutates plain fields and relied on a zone tick breaks. The repo already holds state in signals, which is why ADR-0012 says the design is "zoneless-ready"; remaining risks are plain fields mutated in callbacks.
- `setTimeout` focus handling in `order-create.ts` is DOM-only and unaffected.
- Third-party libraries (Angular Material 20.2.x) must be zoneless compatible.

I would be explicit: no zoneless or OnPush switch has been made or tested here; the claim is only that signals make it feasible. A safe migration path is: set OnPush on shared presentational components first (they only take inputs), run the Karma suite, then try zoneless in a branch.

**Trade-offs and failure modes.**
- OnPush with mutable objects silently stops updating views.
- Zone-based apps pay extra checks on every event but are forgiving.
- Mixing RxJS subscriptions that set plain fields with OnPush needs `async` pipe or `toSignal`.

**Likely follow-ups.**
- Is `effect()` stable in 20? Yes (promoted in 20), but prefer `computed` for derivations; `effect` for side effects only.
- What does `eventCoalescing` do? Reduces change detection runs for bubbled events.
- Does a signal change outside the zone update the view? Yes, signals notify the scheduler.

### Q61. Scenario: Build the order-entry form with dynamic lines, cross-field validation and server-side errors
**Type:** Scenario   **Level:** Intermediate   **Topic:** Reactive forms, validators

**Short answer.** Use typed reactive forms with a `FormArray` for lines, small pure validator functions for rules the API enforces (1 to 50 lines, quantity 1 to 1000, no duplicate product), and map server `errors[]` back onto controls so the user sees the problem next to the field.

**Detailed answer.** The pure validators are in `frontend/oms-ui/src/app/features/orders/order-form.ts` and the component is `order-create.ts`.

```ts
export const uniqueProducts: ValidatorFn = (c) => {
  const seen = new Set<string>();
  const dupes = new Set<string>();
  for (const g of (c as FormArray).controls) {
    const id = (g as FormGroup).get('productId')?.value as string | undefined;
    if (!id) continue;
    if (seen.has(id)) dupes.add(id);
    seen.add(id);
  }
  return dupes.size ? { duplicateProduct: [...dupes] } : null;
};
```

Design points:
- Validators mirror the contract (`MAX_ITEMS = 50`, `MAX_QTY = 1000`, idempotency key pattern `^[A-Za-z0-9_-]{8,64}$`), but they are a convenience: the backend validates again and is authoritative.
- `quantityRange` lets empty values pass so it composes with `Validators.required`; the same convention as built-in validators.
- The form uses `FormBuilder.nonNullable`, and `FormControl<number | null>` for quantity because a number input can be cleared.
- A CUSTOMER user's `customerId` control is prefilled from `AuthStore` and disabled; the server still forces the customer id from the token and answers 403 on mismatch, so tampering gains nothing.
- `applyServerErrors` converts `items[0].quantity` into the path `items.0.quantity`, sets a `server` error on the control, and returns unmatched messages for a banner.
- A total is derived with `computed` over `toSignal(this.form.valueChanges.pipe(startWith(null)))`, which is how reactive-form changes are bridged to signals.
- Accessibility: on invalid submit, focus moves to the first invalid control; after removing a row, focus returns to the add button.

Not verified here: behaviour on browsers other than the headless Chrome used by Karma.

**Trade-offs and failure modes.**
- Client validation can drift from the API; keep constants in one place and add contract tests (the repo has `tests/e2e`).
- `FormArray` typing is awkward; the component casts `controls.items`.
- Server errors set via `setErrors` disappear on the next validation run unless handled.

**Likely follow-ups.**
- Template-driven or reactive? Reactive: testable, explicit.
- Why `updateOn`? Not used; validation runs on change, with debounce only on the list filters.
- How do you test validators? As plain functions without TestBed (`order-form.spec.ts`).

### Q62. Scenario: A customer double-clicks "Place order" on a flaky network. How does the UI avoid duplicate orders?
**Type:** Scenario   **Level:** Advanced   **Topic:** Idempotency, UX

**Short answer.** Two layers: the UI blocks re-entry while a submit is in flight, and every logical submission carries an `Idempotency-Key` that is reused when the previous outcome was unknown and the body is unchanged. The server replays the original 202 for the same key and body, and returns 422 for the same key with a different body.

**Detailed answer.** Code: `submit()` in `features/orders/order-create.ts` and `IdempotencyKeyHolder` in `order-form.ts`.

```ts
submit(): void {
  if (this.submitting()) return; // guards double-click / Enter-key repeats
  ...
  const key = this.keyHolder.keyFor(body);
  this.submitting.set(true);
  this.orders.create(body, key).subscribe({ ... });
}
```

`keyFor(req)` computes a fingerprint (customer, items sorted by `productId`, address) and returns the same key when a pending attempt has the same fingerprint; otherwise it generates `oms-<uuid>` (40 chars, matching `[A-Za-z0-9_-]{8,64}`). `settle()` forgets the key after a definite answer.

Cases:
- Unknown outcome (timeout, network error, 5xx): the user clicks again, the same key goes out, the server returns the original body with `Idempotent-Replay: true`.
- Body edited after a failure: a new key is used, because the same key with a different body would give 422 `IDEMPOTENCY_KEY_REUSED`.
- Definite answer (201-style success or 4xx): `settle()` clears it.

Server side (contract section 13): the key is scoped by the body's `customerId`, the fingerprint is a SHA-256 over a canonical form, keys are retained for 24 hours, and a replay returns the original 202 snapshot rather than current state. A request that fails validation or authorisation does not consume its key.

Why not auto-retry in the interceptor: `retryInterceptor` deliberately skips POST; retrying unsafe methods is a caller decision tied to the key.

Caveat to state honestly: the key lives in component memory, so a page reload between attempts loses it and a fresh key is created; the server cannot link those two submissions. Persisting the pending key in `sessionStorage` would close that gap (not implemented).

**Trade-offs and failure modes.**
- Reusing a key for a changed body is rejected, so the UI must detect changes (fingerprint).
- The server's retention window bounds how late a safe retry can be.
- Rate limiting (429 with `Retry-After`) can interact with retries: honour the header.

**Likely follow-ups.**
- Why 202 and not 201? Validation and the saga are asynchronous; the order starts as `CREATED`.
- Is the key a secret? No, but it is a correlation handle; do not log it with the body.
- What does the user see for a replay? The same order; the UI treats it as success.

### Q63. Scenario: A customer edits the client code to reach the Inventory screen. What actually stops them?
**Type:** Scenario   **Level:** Intermediate   **Topic:** Route guards versus real authorization

**Short answer.** Nothing in the browser stops them: guards only hide screens. The backend does: the gateway applies role rules as defense in depth and each service re-validates the JWT and enforces roles and ownership, so a CUSTOMER token gets 403 (or 404 for foreign objects) regardless of what the UI shows.

**Detailed answer.** `frontend/oms-ui/src/app/core/auth/guards.ts` states it directly: "Route guards are a UX convenience only".

```ts
export const roleGuard: CanActivateFn = (route) => {
  const auth = inject(AuthStore);
  const router = inject(Router);
  if (!auth.isAuthenticated()) return router.createUrlTree(['/login']);
  const wanted = route.data['roles'] as Role[] | undefined;
  return auth.hasAnyRole(wanted) ? true : router.createUrlTree(['/forbidden']);
};
```

A user can open devtools, change `sessionStorage` (`oms.session`), patch the bundle, or call the API with curl. What holds the line:
1. Gateway (`gateway/.../security/EdgeSecurityConfig.java`): validates signature, `iss`, `aud`, `exp`; applies `hasAnyRole(OPERATOR, ADMIN)` to `/api/v1/ops/**`, and denies `/api/v1/internal/**`.
2. Services: each runs the same resource-server chain (`JwtSecurityAutoConfiguration`), URL rules via `OmsAuthorizeRequestsCustomizer` (for example `OrderSecurityConfig`), and `@PreAuthorize` on controllers. Role rules run before request binding, so unauthorised callers get 403 before 400 and no validation oracle.
3. Resource level: `CurrentUser.canAccessCustomer(ownerId)`; another customer's order is 404 to prevent enumeration.

A forged token fails signature validation; a real CUSTOMER token carries `roles: ["CUSTOMER"]` and cannot be altered without the signing key. In demo mode that key is a shared HS256 secret (anyone holding it can mint tokens), which is why production moves to RS256/JWKS (Q80).

What guards still provide: avoiding dead-end navigation and giving a friendly 403 page. Also never ship secrets in the bundle: `config.json` holds only `apiBaseUrl` and poll intervals.

**Trade-offs and failure modes.**
- UI role hints can go stale if roles change mid-session; the next API call corrects it with 403.
- Duplicating role rules in UI and backend risks drift; the backend is the source of truth.
- The token in `sessionStorage` is readable by XSS (Q84).

**Likely follow-ups.**
- Should menu items hide for roles? Yes, as UX; not as protection.
- How do you test it? `SecurityApiTest` style negative tests: 401, 403, 404, 400, 413, 409.
- What about lazy chunks of admin screens? They contain no secrets; knowing a route exists is not a vulnerability.

### Q64. Scenario: Ship one Angular build to dev, test and prod without rebuilding
**Type:** Scenario   **Level:** Intermediate   **Topic:** Runtime configuration

**Short answer.** Load a non-secret `config.json` at startup with `provideAppInitializer`, instead of baking `environment.ts` values into the bundle. The nginx image generates the file from environment variables, so the same artifact runs everywhere.

**Detailed answer.** `core/config.service.ts`:

```ts
async load(fetchFn: typeof fetch = fetch): Promise<void> {
  try {
    const res = await fetchFn('config.json', { cache: 'no-store' });
    if (res.ok) { this.set((await res.json()) as Partial<AppConfig>); }
  } catch {
    // keep defaults: the proxy/nginx convention /api/v1 works without config.json
  }
}
```

`app.config.ts` registers `provideAppInitializer(() => inject(ConfigService).load())`, so the app does not render until configuration is loaded (or defaults are chosen). `ConfigService` keeps the config in a signal; `apiBaseUrl` strips trailing slashes. The interceptors read `apiBaseUrl` to decide what is an API call.

In the container (`frontend/oms-ui/nginx.conf` and `Dockerfile`), the entrypoint substitutes only `API_GATEWAY_URL` and `OMS_API_BASE_URL` (`NGINX_ENVSUBST_FILTER`), and nginx returns the JSON for `location = /config.json` with `Cache-Control: no-store`. `/api/` is proxied to the gateway, so the browser talks to one origin and no CORS configuration is needed for the UI.

Security: only non-secret values belong here (everything in a SPA is public). `cache: 'no-store'` avoids stale configuration after a redeploy.

Honest limits: the Dockerfile header says it was not built on the author machine; the CI `docker-ui` job defines the build but has not been run locally. The nginx CSP (`script-src 'self'`) is recommended but marked "confirm before release".

**Trade-offs and failure modes.**
- A failed `config.json` fetch silently falls back to defaults; a misconfiguration could hide behind it. Log or surface it in non-production.
- Blocking startup on a fetch adds latency; keep the file tiny.
- `environment.ts` is simpler when only one environment exists but forces a rebuild per environment.

**Likely follow-ups.**
- Why not build-time env? Rebuilding per environment breaks "build once, promote".
- Can you put an API key in config.json? No.
- What if `/config.json` is cached by a CDN? `no-store` and unique hashed asset names for everything else.

### Q65. How are TypeScript strictness and generics used to keep the UI safe?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** TypeScript

**Short answer.** The project compiles with `strict`, `noImplicitReturns`, `noPropertyAccessFromIndexSignature`, and Angular `strictTemplates`; shared UI such as the data table and list store are generic, and API shapes in `core/models.ts` mirror the contract, so mismatches are caught at compile time rather than at runtime.

**Detailed answer.** `frontend/oms-ui/tsconfig.json` sets `strict: true`, `noImplicitOverride`, `noPropertyAccessFromIndexSignature`, `noImplicitReturns`, `noFallthroughCasesInSwitch`, target ES2022, and Angular `strictTemplates`, `strictInjectionParameters`, `strictInputAccessModifiers`.

Patterns in the code:
- Generic components: `Column<T>` with `value: (row: T) => string | number | null | undefined` and `class DataTable<T>` with `input.required<Column<T>[]>()`.
- Generic store: `ListStore<T, F extends object>` takes a `fetchFn: (q: ListQuery<F>) => Observable<Page<T>>`, so each screen supplies its row and filter types.
- Literal unions instead of enums: `LoadStatus = 'idle' | 'loading' | 'loaded' | 'error'`, `Role` as a union of `'CUSTOMER' | ...` (as used in `hasAnyRole(session, wanted: readonly Role[] | undefined)`).
- Pure, typed state transitions: `listLoaded<T>(data: Page<T>): ListState<T>`.
- `unknown` for caught errors and normalisation into `ApiError` (`normalizeError(e: unknown)`), instead of `any`.
- `readonly` signals: `this._session.asReadonly()` prevents outside writes.
- Defensive parsing at a trust boundary: `parseStoredSession` validates `sessionStorage` content field by field and returns `null` when malformed or expired.

Where types stop protecting you: JSON from the network is not validated at runtime (types are erased), so `res.json() as Partial<AppConfig>` is a cast; a schema validator, or generating clients from the committed OpenAPI export (`specs/openapi/` currently holds only `inventory-service.json`), would add real checking. The repo hand-writes models; I would not claim generated clients exist.

**Trade-offs and failure modes.**
- Hand-written models drift from the backend; OpenAPI-generated types reduce this.
- Over-clever generics hurt readability; the repo keeps them at component and store boundaries.
- `strictTemplates` makes upgrades noisier but catches template bugs early.

**Likely follow-ups.**
- `type` or `interface`? Interfaces for object shapes that may extend, unions as `type`.
- Why `unknown` not `any` in catch? Forces narrowing.
- How do you share types with the backend? `docs/architecture/00-shared-contract.md` is the contract and `core/models.ts` mirrors it by hand; OpenAPI codegen is a possible next step.

## RxJS and Signals

### Q66. switchMap, mergeMap, concatMap or exhaustMap: how do you choose?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** Higher-order mapping operators

**Short answer.** All four map each outer value to an inner observable; they differ in concurrency. `switchMap` cancels the previous inner (latest wins), `mergeMap` runs all in parallel, `concatMap` queues in order, `exhaustMap` ignores new triggers while one is running.

**Detailed answer.** Choose by what a stale or duplicate result means:
- Reads where only the newest result matters: `switchMap`. `ListStore` in `core/state/list-store.ts` uses `reload$.pipe(startWith(undefined), switchMap(...))`, and its doc says "`switchMap` guarantees that a stale response can never overwrite a newer query". The ops dashboard also uses `merge(timer(0, REFRESH_MS).pipe(filter(() => this.auto())), this.manual$).pipe(switchMap(() => forkJoin(...)))`.
- Writes where order matters (for example sequential saves of one document): `concatMap`.
- Independent parallel work with no ordering need: `mergeMap` (with a concurrency limit as the second argument for large fan-out).
- Prevent double submission of a command: `exhaustMap`. The order form achieves the same effect with an explicit `if (this.submitting()) return` guard; `exhaustMap` on a click subject would be the declarative alternative.

Careful with `switchMap` for writes: cancelling the subscription cancels the HTTP request in the browser, but the server may already have processed it, so the client loses the outcome. That is why writes in this repo use an idempotency key instead.

Inside `ListStore`, error handling is placed within the inner pipe (`catchError(... of(listFailed(...)))`), so an error does not terminate the outer `reload$` stream; putting `catchError` outside `switchMap` would kill the stream after the first failure.

Also relevant: HTTP observables from `HttpClient` complete after one emission, so `switchMap`, `mergeMap` and `concatMap` behave differently only when triggers overlap.

**Trade-offs and failure modes.**
- `mergeMap` without a limit can flood the backend or the browser connection pool.
- `exhaustMap` drops triggers silently; fine for clicks, wrong for data that must not be lost.
- `switchMap` cancellation hides backend completion, so it is unsuitable for non-idempotent commands.

**Likely follow-ups.**
- Which for typeahead? `debounceTime` plus `distinctUntilChanged` plus `switchMap`.
- Which for a queue of uploads? `concatMap` or `mergeMap` with limit.
- Does `switchMap` cancel the server work? No, only the client subscription.

### Q67. Scenario: Poll order status with backoff until the order is terminal, and stop cleanly
**Type:** Scenario   **Level:** Advanced   **Topic:** expand, timer, takeUntil

**Short answer.** Order status is eventually consistent because of the saga, so the detail screen polls. `pollUntil` emits immediately, then re-polls with growing delay (factor 1.5, capped), completes when the value is terminal, and stops when `stop$` emits.

**Detailed answer.** `core/state/polling.ts`:

```ts
return defer(fetchFn).pipe(
  expand((value, index) =>
    isDone(value) ? EMPTY : timer(backoffDelay(index, opts)).pipe(switchMap(() => fetchFn())),
  ),
  takeUntil(stop$),
);
```

`backoffDelay(index, opts)` is a pure function: `Math.min(opts.maxMs, Math.round(opts.initialMs * (opts.factor ?? 1.5) ** index))`. `expand` is recursive: each emitted value is fed back into the projection, which returns either `EMPTY` (end of recursion) or a delayed fetch. The terminal value is still emitted before completion. An error from `fetchFn` terminates the stream so the screen can show it and offer a manual refresh.

`OrderDetailStore` (`features/orders/order-detail.store.ts`) uses it with config values (`pollIntervalMs` 1500, `pollMaxIntervalMs` 8000) and `forkJoin({ order, history, saga })`; the saga call for staff is wrapped in `catchError(() => of(null))` so a failed optional call does not abort the poll. It stops through `stop$`, `destroyRef.onDestroy`, or the terminal status, and exposes signals (`snapshot`, `polling`, `terminal`, `cancellable`).

Testing: `polling.spec.ts` uses `fakeAsync`, `tick(100)`, `tick(199)`, `tick(1)` to prove the 100, 200 ms delays and that no polling continues after the terminal value, and that `stop$` halts it.

Why polling and not WebSocket or SSE: documented as a possible later upgrade if polling load matters (`docs/architecture/angular-architecture.md` section 6).

**Trade-offs and failure modes.**
- Polling load scales with open detail screens; backoff and a cap limit it, but there is no jitter.
- A hidden browser tab keeps polling; `document.visibilityState` handling is not implemented.
- A single failed poll ends polling instead of retrying; GET retry interceptor covers transient failures first.

**Likely follow-ups.**
- Why `expand` and not `interval`? `interval` ignores response time and can overlap; `expand` waits for each fetch.
- Why `defer`? So the first fetch happens at subscription time.
- Why a pure `backoffDelay`? It is testable without time.

### Q68. Scenario: Filters with search-as-you-type must not show stale results
**Type:** Scenario   **Level:** Intermediate   **Topic:** debounceTime, switchMap, forms

**Short answer.** Debounce the form's `valueChanges`, skip invalid states, push filters into a store that resets to page 0, and let `switchMap` in the store discard in-flight requests for older queries.

**Detailed answer.** `features/orders/order-list.ts`:

```ts
this.form.valueChanges.pipe(debounceTime(300), takeUntilDestroyed()).subscribe(() => {
  if (this.form.invalid) return;
  const v = this.form.getRawValue();
  this.store.setFilters({ status: v.status, from: dateBound(v.from, false), to: dateBound(v.to, true), q: v.q.trim() });
});
```

Pieces:
- A cross-field validator (`dateRange`) marks `to < from` invalid; invalid filters are not sent.
- `dateBound(day, end)` is a pure function converting `yyyy-mm-dd` into an ISO UTC bound.
- `ListStore.setFilters` applies `withFilters`, which always sets `page: 0`, and calls `refresh()`; `setPage` and `toggleSort` follow the same shape. `withSort` also resets the page.
- `switchMap` in the store ensures a slow response for query A cannot overwrite the response for query B.
- Loading, error, empty and total states are `computed` signals consumed by `app-data-table` (with `aria-busy` and retry).
- Sort and page are validated by the backend against a whitelist: out-of-range `size` or unknown sort fields give 400 `VALIDATION_FAILED` rather than silent clamping (`PageRequestFactory`).

Improvement I would mention: `distinctUntilChanged` on the serialised filter would avoid duplicate requests when the user types and deletes the same character; it is not present. Another is cancelling when the component is destroyed: `takeUntilDestroyed()` does that for the form subscription, and `ListStore` uses `takeUntilDestroyed(destroyRef)` for its stream.

**Trade-offs and failure modes.**
- 300 ms debounce delays feedback; too low floods the API.
- `switchMap` cancels requests the server may still execute; harmless for GETs.
- A deep link or reload loses filters because query state is not synced to the URL (not implemented).

**Likely follow-ups.**
- Why `getRawValue`? It includes disabled controls.
- Why reset the page on filter change? Otherwise you may land beyond the last page.
- How would you sync to the URL? Router query params with `withComponentInputBinding`.

### Q69. What are signal, computed and effect, and which Angular signal APIs are experimental?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** Signals API

**Short answer.** `signal()` holds a value, `computed()` derives a lazily evaluated, memoised value, and `effect()` runs side effects when dependencies change. In Angular 20 `signal`, `computed`, `effect`, `linkedSignal`, `toSignal` and `toObservable` are stable, while `resource`/`rxResource` are still experimental.

**Detailed answer.** Usage in the repo:
- `signal` + `asReadonly`: `AuthStore` (`_session`), `CartStore` (`_lines`), `OrderDetailStore`.
- `computed`: `isStaff = computed(() => this.roles().some((r) => r === 'OPERATOR' || r === 'ADMIN'))`, `CartStore.total`, `ListStore.items`, `empty`.
- `effect` with `untracked`, in `OrderDetail`:

```ts
effect(() => {
  const id = this.id();
  untracked(() => this.store.start(id));
});
```

The id input is the only dependency; starting a store reads and writes other signals, which `untracked` keeps from becoming dependencies (otherwise you can create loops or surprising re-runs).

Rules of thumb:
- Derive state with `computed`, not `effect` plus `set`.
- Use `effect` for side effects on the outside world (DOM, logging, calling a store). Since Angular 19 signals may be written inside effects, which makes derived-state-via-effect tempting; prefer `computed` or `linkedSignal` for that.
- Signals use reference equality by default; updating with a mutated object does not notify. The pure update functions (`addToCart`, `listLoaded`) return new objects for exactly this reason.
- `input()` and `output()` are signal-based component APIs, `input.required<T>()` fails at compile time if missing.

Experimental or newer, flagged: `resource()` and `rxResource()` (declarative async data loading with `loading`/`error`/`value` signals) are experimental in 20, so this repo does not use them and instead hand-writes `ListStore`. Check the Angular release notes before using them in production code. Signal Forms are also experimental in later releases and are not part of this app.

**Trade-offs and failure modes.**
- Mutating objects inside signals without replacing them gives silent non-updates.
- Effects that set signals can cause cascading updates; keep them small.
- Reading signals in async callbacks outside a reactive context does not track.

**Likely follow-ups.**
- `computed` versus method in template? `computed` memoises; a method runs on every check.
- What is `linkedSignal`? A writable signal that resets from a source signal; stable in 20, not used here.
- Do signals replace RxJS? No: signals for state, RxJS for events and IO (Q71).

### Q70. Scenario: Components leak subscriptions and timers. How do you prevent it?
**Type:** Scenario   **Level:** Intermediate   **Topic:** takeUntilDestroyed, DestroyRef

**Short answer.** Tie every long-lived stream to the component's lifetime with `takeUntilDestroyed()`, a `DestroyRef.onDestroy` callback, or a `stop$` subject, and prefer `toSignal` or the `async` pipe, which unsubscribe automatically. HTTP calls that complete on their own need no teardown.

**Detailed answer.** Patterns used:
- Form streams: `this.form.valueChanges.pipe(debounceTime(300), takeUntilDestroyed()).subscribe(...)` in the constructor (an injection context, so no `DestroyRef` argument is required).
- Outside an injection context pass the ref: `ListStore` is constructed in an injection context and does `takeUntilDestroyed(destroyRef)` with `inject(DestroyRef)`.
- Component-scoped store: `OrderDetailStore` is `@Injectable()` provided in `OrderDetail`'s `providers`, so Angular destroys it with the component; its constructor registers `this.destroyRef.onDestroy(() => this.stopPolling())`, which emits on `stop$` and ends the poll.
- Dashboard timer: `OpsDashboard` subscribes to `merge(timer(...), manual$)` with `takeUntilDestroyed()`.
- Session expiry timer: `AuthStore` clears the previous `setTimeout` before setting a new one and caps the delay at `MAX_TIMER_MS = 2_147_483_000`, because browsers overflow `setTimeout` beyond 2^31-1 ms and fire immediately.
- Signals: `toSignal(this.bp.observe(...).pipe(map(...)), { initialValue: false })` in `app.ts` handles its own subscription.

Diagnosing leaks: the Angular devtools profiler and the browser memory tab (growing detached DOM nodes or listener counts), plus checking Network for polling that continues after navigating away.

Things to avoid: manual `subscribe` in `ngOnInit` with no teardown; nested subscribes (use `switchMap`); `Subject`s that never complete; `setInterval` without `clearInterval`.

**Trade-offs and failure modes.**
- `takeUntilDestroyed()` called outside an injection context throws; pass `DestroyRef`.
- `takeUntil` must be the last operator to avoid leaks via later operators in the chain.
- Root-provided stores live for the app lifetime; do not subscribe in them without teardown logic.

**Likely follow-ups.**
- `async` pipe or `toSignal`? Either; `toSignal` fits signal-based templates and needs an `initialValue` or `requireSync`.
- Why `takeUntil` last? Operators after it can still hold subscriptions.
- Does `HttpClient` need unsubscribe? Not for completed single requests; unsubscribing cancels in flight.

### Q71. When should state live in RxJS and when in signals, and how do you bridge them?
**Type:** Conceptual   **Level:** Advanced   **Topic:** rxjs-interop

**Short answer.** Signals model current state that templates read synchronously; RxJS models events over time (HTTP, timers, user input with debounce, cancellation). Bridge with `toSignal` (observable to signal) and `toObservable` (signal to observable).

**Detailed answer.** Division in this repo (ADR-0012):
- Signals: `AuthStore`, `CartStore`, list state, derived values with `computed`.
- RxJS: IO and time: interceptors, `pollUntil`, `debounceTime` on forms, `forkJoin` for dashboard aggregation (`features/dashboard/dashboard.ts`, `failed-orders.ts`).
- Bridges: `toSignal(this.form.valueChanges.pipe(startWith(null)), { initialValue: null })` in `order-create.ts`, which feeds `computed` totals; `toSignal(this.bp.observe('(max-width: 900px)')...)` in `app.ts`; stores subscribe to observables and `set` signals in `next` callbacks.

Properties to know:
- Signals are synchronous and glitch-free, always have a value, and dedupe equal values; observables can be asynchronous, can error, and complete.
- `toSignal` subscribes immediately, unsubscribes on destroy, needs an `initialValue` (or `requireSync: true` for synchronous sources), and rethrows source errors when the signal is read, so handle errors with `catchError` before bridging.
- `toObservable` emits asynchronously using an effect, so it only emits the latest value after a change detection pass; it can skip intermediate values.
- Do not store HTTP results only in observables read by many components; each `subscribe` triggers another request unless shared (`shareReplay` with care).

Redux mapping: reducers become pure functions (`listLoaded`, `withFilters`); `useSelector` becomes reading a `computed`; thunks and sagas become RxJS pipelines (see Q74).

Honest limit: ADR-0012 admits there is no standard pattern for caching or optimistic updates; server data is refetched rather than mirrored, which keeps code small but costs requests.

**Trade-offs and failure modes.**
- Mixing both without a rule leads to double sources of truth.
- `toSignal` errors surfacing in templates are confusing.
- `shareReplay({ refCount: true, bufferSize: 1 })` is needed to avoid leaking a source subscription.

**Likely follow-ups.**
- Can an `effect` replace `toObservable`? For side effects yes; for operators like `debounceTime` use `toObservable`.
- Is `toSignal` stable in 20? Yes.
- Where would NgRx SignalStore fit? When several stores mutate the same entity (ADR-0012 revisit triggers).

### Q72. Scenario: The dashboard calls several endpoints and one fails. How do you avoid a blank screen?
**Type:** Scenario   **Level:** Intermediate   **Topic:** forkJoin, catchError

**Short answer.** `forkJoin` fails as a whole if any source errors, so isolate each source with its own `catchError` that converts the error into a value, then render partial results with per-card error banners and retry.

**Detailed answer.** `features/ops/ops-dashboard.ts`:

```ts
forkJoin({
  cards: forkJoin(
    SERVICES.map((service) =>
      this.api.messaging(service).pipe(
        map((data): ServiceCard => ({ service, data, error: null })),
        catchError((e: unknown) => of<ServiceCard>({ service, data: null, error: normalizeError(e) })),
      ),
    ),
  ),
  health: this.api.health().pipe(
    map((h) => ({ h, e: null as ApiError | null })),
    catchError((e: unknown) => of({ h: null as HealthAggregate | null, e: normalizeError(e) })),
  ),
}),
```

Why it works:
- `forkJoin` waits for all sources to complete and emits once with the last value of each; HTTP sources complete after one emission, so it fits.
- The error becomes data (`error: ApiError`), so the template renders `@if (c.error) { <app-error-banner ...> } @else if (c.data; as m) { ... }`. The contract also says the gateway health endpoint always returns HTTP 200 with a per-service status, so a down service is reported in the body (`docs/architecture/00-shared-contract.md` section 5.5).
- Refresh uses `switchMap`, so a new tick cancels a slow previous refresh.
- `OrderDetailStore` applies the same idea to the optional saga call: `this.api.saga(id).pipe(catchError(() => of(null)))`, with the page still working for customers (who do not call the saga endpoint at all because `isStaff()` is false).

Alternatives: `combineLatest` for streams that stay alive; `Promise.allSettled`-like behaviour through the pattern above; `forkJoin` with an empty source completes without emitting, a classic bug when a list is empty (`SERVICES` is a constant so it cannot be empty here).

Visible failure matters: an operator dashboard that hides a failing service would be worse than none, so the card shows the normalised error with the correlation id.

**Trade-offs and failure modes.**
- Partial results can mislead if not clearly labelled; show per-card status.
- Parallel calls multiply load on each refresh interval.
- `forkJoin` on an empty array emits nothing and completes.

**Likely follow-ups.**
- `forkJoin` versus `combineLatest`? Single-shot versus continuing streams.
- What if one call hangs? Add `timeout()` per source.
- How do you test it? Provide stub APIs that throw for one service and assert the card state.

### Q73. Scenario: How do you unit test time-based RxJS code and signal stores without flaky tests?
**Type:** Scenario   **Level:** Intermediate   **Topic:** fakeAsync, pure functions, HttpTestingController

**Short answer.** Push logic into pure functions and test those directly; test time-based streams with `fakeAsync` and `tick`; test HTTP-dependent code through the same interceptor chain backed by `HttpTestingController`. Avoid real timers and sleeps.

**Detailed answer.** The repo applies three layers (see `docs/architecture/angular-architecture.md` section 8):

1. Pure functions with no TestBed: the list "reducers" (`listLoading`, `listLoaded`, `withFilters`, `withPage`, `withSort`, `nextSort`), `backoffDelay`, `normalizeError`, cart functions, form validators. `list-store.spec.ts` asserts that transitions do not mutate their input and that changing filters resets to page 0.
2. Virtual time: `core/state/polling.spec.ts` drives `pollUntil` with `fakeAsync`:

```ts
tick(100);
expect(seen).toEqual(['CREATED', 'VALIDATED']);
tick(199); // next delay is 200ms
expect(seen.length).toBe(2);
tick(1);
expect(seen).toEqual(['CREATED', 'VALIDATED', 'FULFILLED']);
```

It also proves that after the terminal value no more polling happens (`tick(5000)` leaves the call count unchanged) and that `stop$` halts polling, with `discardPeriodicTasks()` in the endless case.
3. HTTP: `testing/test-utils.ts` exposes `httpTestProviders()` that registers the same four interceptors as `app.config.ts` plus `provideHttpClientTesting()`, so interceptor behaviour (auth header, retry only for GET, 401 logout) is verified end to end in `interceptors.spec.ts`.

Other techniques: marble tests with `TestScheduler` for complex operator chains (not used in this repo; `fakeAsync` was sufficient); `runInInjectionContext` to construct stores that call `inject(DestroyRef)` (the list-store spec imports `runInInjectionContext` and `createEnvironmentInjector` for this); component tests render with the Material harness where needed.

Tests run with Karma and Jasmine through `ng test --watch=false --browsers=ChromeHeadlessNoSandbox`. Honest limitation: only the headless browser used by Karma was exercised; browser-level end-to-end checks live in `tests/e2e`.

**Trade-offs and failure modes.**
- `fakeAsync` does not cover real async APIs like `fetch` unless wrapped.
- Marble tests are precise but have a learning curve.
- Over-mocking stores hides integration problems; keep one HTTP-level test per feature.

**Likely follow-ups.**
- Why test reducers purely? Fast, deterministic, no framework.
- What does `discardPeriodicTasks` do? Clears timers left pending in `fakeAsync` zones.
- How do you test a guard? Run it in an injection context with a fake `AuthStore` (`guards.spec.ts`).

## React/Redux versus Angular mapping

### Q74. How do React and Redux concepts map to what this Angular project does?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** React to Angular mapping

**Short answer.** The repo contains both: the Angular UI (`frontend/oms-ui`, required by the brief) and a feature-equivalent React 19 + Redux Toolkit UI (`frontend/oms-ui-react`, added later at the user's explicit request, ADR-0013). The Angular-side mapping is: `useState` is `signal()`, `useMemo`/selectors are `computed()`, reducers are exported pure functions, dispatch is a store method call, thunks and sagas are RxJS pipelines, middleware is HTTP interceptors.

**Detailed answer.** The mapping, with the concrete file for each:

| React / Redux | Angular here |
|---|---|
| Function component and props | standalone `@Component`, `input()`; route params via `withComponentInputBinding` |
| Callback props | `output()` (see `data-table.ts`: `pageChange`, `sortChange`, `retry`) |
| `useState` | `signal()` |
| `useMemo`, reselect | `computed()` (`CartStore.total`) |
| `useEffect` | `effect()` for signal-driven side effects (`OrderDetail`), RxJS plus `takeUntilDestroyed` for IO |
| Redux slice | store class (`AuthStore`, `CartStore`, `ListStore`) |
| Reducer | pure functions `listLoaded`, `withFilters`, `addToCart` |
| Context | dependency injection (`providedIn: 'root'` or component `providers`) |
| Router loaders and guards | `canActivate` functions, `loadComponent` |
| Axios interceptors | `HttpInterceptorFn` chain |
| `React.lazy` + Suspense | `loadComponent` dynamic import |
| `.env` build variables | runtime `config.json` |
| Redux DevTools | none by default |

Differences that matter in an interview:
- Angular DI replaces prop drilling and context; services are singletons by default.
- Angular templates are not JSX; control flow is `@if`, `@for` (with mandatory `track`, similar in spirit to React keys).
- Angular ships forms, HTTP, routing and DI, whereas React uses libraries (React Router, React Query or RTK Query, React Hook Form).
- There is no built-in query cache in the Angular app; it refetches. The React app uses RTK Query (cache keyed by endpoint and arguments, tag invalidation, polling), so the same behaviour is implemented two different ways.
- Redux enforces one-way flow by convention and tooling; the signal stores rely on discipline (private writable signal, public readonly, update through methods). ADR-0012 lists this as a negative consequence.

State the status honestly: the React side of every row is real code with tests (chapter 12b shows both versions of each concept; the React app's README has the full side-by-side table), verified by 207 Vitest tests and the same 18 Playwright specs passing on both UIs. What is not verified: the React container image, an accessibility audit, and any performance comparison between the two frameworks.

**Trade-offs and failure modes.**
- Naively porting Redux ceremony (actions, reducers, selectors) into Angular adds boilerplate for state that is refetched anyway.
- Signals lack time travel; debugging relies on logs and the Angular devtools.
- Component-level state is simpler than a global store, but breaks for auth and cart state that survive navigation.

**Likely follow-ups.**
- What is `useEffect` cleanup in Angular? `DestroyRef.onDestroy`, `takeUntilDestroyed`, or the `effect` cleanup callback.
- Where is the "store" in `ListStore`? A class holding a private signal and exposing readonly signals.
- Is there a Redux DevTools equivalent? NgRx has one; this repo does not use NgRx.

### Q75. Scenario: The interviewer asks about your React experience. What do you say, given that this repo has both an Angular and a React front end?
**Type:** Scenario   **Level:** Intermediate   **Topic:** Speaking truthfully about experience

**Short answer.** Say exactly what is true: the project began as Angular 20 (the brief), and a React + Redux Toolkit UI was added later on the same contract. You can point at real React code, 207 passing tests and the shared e2e specs, but this is a demo project built for interview preparation, not production experience; keep "shipped in production" separate from "built and tested here".

**Detailed answer.** Why honesty matters: interviewers probe with specifics (hooks dependency arrays, reconciliation, Redux Toolkit slices, selector memoisation). Invented experience fails on the second follow-up, and it damages credibility on the rest of the answers.

A truthful structure:
1. Scope: "This demo has two UIs on one gateway contract: Angular 20 with signals and RxJS, and React 19 with Redux Toolkit and RTK Query. Both pass the same 18 Playwright specs."
2. Bridge: "The repo documents a mapping (`docs/architecture/angular-architecture.md` section 9, ADR-0012): `useState` to `signal`, selectors to `computed`, reducers to pure functions like `listLoaded`, thunks to RxJS operators, middleware to interceptors."
3. Real depth: pick one concept and explain it concretely, for example why `ListStore` uses `switchMap` so a stale response cannot overwrite a newer query (the same race you handle with request cancellation or ignoring stale responses in React effects).
4. Actual React exposure beyond this repo: name it exactly (jobs, side projects, reviews) and what you did and did not do. If this repo is the extent, say so: "I built and tested a React and Redux Toolkit UI here; I have not run React in production, and I have not audited it for accessibility."
5. Learning plan: a small exercise (port the order list screen) rather than a claim.

Useful comparisons you can defend without React production experience:
- React re-renders components and diffs a virtual DOM; Angular signals track fine-grained dependencies and update views that read them.
- `useEffect` dependency arrays are manual; Angular `effect` tracks signal reads automatically (and `untracked` opts out, as in `OrderDetail`).
- React Query/RTK Query solve caching and invalidation, which this repo does not implement.

Avoid: claiming production React experience this repo does not prove, naming hooks you cannot explain, or saying "we used Redux" about the Angular stores (they are signal stores, not Redux or NgRx).

**Trade-offs and failure modes.**
- Over-apologising weakens the answer; state scope calmly and pivot to depth.
- Describing the mapping as experience is the failure mode that costs trust.
- If the role is React-first, say so and discuss ramp-up time candidly.

**Likely follow-ups.**
- "So you have not used hooks in production?" Answer directly, then show `useOrderPolling`, `useSessionExpiry` and `useDebouncedValue` and explain their dependency arrays and cleanups.
- "What would you do next with the React app?" Generate the types from the OpenAPI export, run axe and Lighthouse, move Emotion to a CSP nonce, build and scan the image.
- "Why Angular here?" Batteries-included forms, DI and HTTP suit a large enterprise team (ADR-0012 context).

### Q76. When would you adopt NgRx or SignalStore instead of plain signal stores, and how does that compare with Redux Toolkit?
**Type:** Conceptual   **Level:** Advanced   **Topic:** State management strategy

**Short answer.** Stay with signal stores while shared client state is small and server data is refetched. Move to NgRx SignalStore first when several stores write the same entity or you need conventions; use full NgRx for event-sourced client logic, devtools or time travel. Redux Toolkit plus RTK Query is the closest React analogue.

**Detailed answer.** ADR-0012 records the decision and the revisit triggers: more than a few stores writing the same entity, need for time-travel or devtools, cross-tab undo, or an offline write queue.

Options compared:
- Plain signal stores (chosen): `AuthStore`, `CartStore`, `ListStore`; very little code, tests are plain TypeScript; negatives are no devtools, conventions not enforced, and hand-rolled caching and optimistic updates.
- NgRx SignalStore: signal-based, lighter than classic NgRx; adds structure (`withState`, `withComputed`, `withMethods`, features). Stable since Angular 19 at the NgRx level. Natural next step.
- Classic NgRx (store, effects, selectors, devtools): strong for large teams and complex client state; ceremony is excessive for data that is refetched.
- Redux Toolkit: `createSlice` reduces Redux boilerplate; RTK Query adds fetching and caching; in Angular the nearest analogues are NgRx plus entity or SignalStore and a custom resource layer, since Angular has no built-in query cache.

Decision criteria I would state:
1. How much state is truly client-owned? Here: auth, cart, list query, polling status.
2. How many writers per entity? Few.
3. Need for audit or replay of client actions? No.
4. Team size and onboarding: conventions in a small team versus enforced structure in a large one.
5. Cost of server refetch versus cache complexity. The repo accepts extra requests for simplicity; if load matters, add a caching layer or the experimental `resource` API after reading release notes.

Be careful about version claims: say "check the NgRx and Angular release notes for the version you ship" rather than quoting from memory.

**Trade-offs and failure modes.**
- Adopting NgRx early adds action and effect boilerplate for little gain.
- Staying with ad hoc stores too long leads to inconsistent patterns and duplicated fetch logic.
- A hybrid is fine: global store for shared entities, local signals for view state.

**Likely follow-ups.**
- What is the first sign you outgrew plain stores? Two stores updating the same entity with divergent copies.
- Do you need effects in SignalStore? RxJS methods via `rxMethod` cover it.
- How would you add optimistic updates now? Update the signal, call the API, roll back on error.

### Q77. Scenario: Port a React + Redux Toolkit orders screen (RTK Query, paging, filters) to this Angular codebase
**Type:** Scenario   **Level:** Advanced   **Topic:** Migration mapping

**Short answer.** Map the RTK Query endpoint to a typed method on `OrderApi`, the slice to a `ListStore`, the filter form to a typed reactive form with debounce, the table to `app-data-table`, and caching to an explicit refetch. The pieces already exist; the work is wiring and tests.

**Detailed answer.** Suppose the React screen has `useGetOrdersQuery({ page, size, sort, status, q })`, a Redux slice for filters, and a table component.

Step by step in this codebase:
1. API: add or reuse `OrderApi.list(filter)` in `core/api/apis.ts` (it returns `Observable<Page<Order>>`). Authorization is already enforced by the backend: customers see only their own orders because the filter is forced from the token.
2. State: `new ListStore<Order, OrderFilter>((q) => this.api.list({ ...q.filters, page: q.page, size: q.size, sort: q.sort }), {}, 20, 'createdAt,desc')`, which is exactly the construct in `features/orders/order-list.ts`. The store replaces the slice plus the query hook: `store.items()`, `store.loading()`, `store.error()`, `store.empty()`, `store.total()`.
3. Filters: typed `FormBuilder.nonNullable` form; `valueChanges.pipe(debounceTime(300), takeUntilDestroyed())` calls `store.setFilters(...)`, which resets to page 0.
4. UI: `<app-data-table [columns] [rows] [loading] [error] [total] [page] [size] [sort] (pageChange) (sortChange) (retry)>` replaces the React table; sorting buttons carry `aria-sort`.
5. Caching and invalidation: RTK Query's tags (`invalidatesTags`) become a call to `store.refresh()` after a mutation (cancel, retry); there is no cross-component cache, which is the gap to discuss.
6. Navigation: row link `routerLink` to `orders/:id`; the detail screen reads the id as `input.required<string>()`.
7. Tests: reducers as pure functions, interceptors with `HttpTestingController`, screen spec with provider setup from `testing/test-utils.ts`.

Differences to flag: Angular needs `track` expressions in `@for`; subscriptions must be torn down (Q70); the sort and page whitelist is validated on the server (400, not clamped), so UI controls must send allowed fields only.

Honest framing: the Angular app was written first and the React app second; neither was generated from the other. The reverse port (Angular to React) exists as real code in `frontend/oms-ui-react`: `useListQuery` plus `useGetOrdersQuery` replace `ListStore`, and tag invalidation replaces `store.refresh()`.

**Trade-offs and failure modes.**
- Losing automatic caching and deduplication means more requests; mitigate with debouncing and `switchMap`.
- Copying Redux structure (actions, reducers) into Angular would add needless code.
- Optimistic UI is not provided; implement per action if needed.

**Likely follow-ups.**
- Where does `invalidatesTags` go? A `refresh()` call or a shared event stream.
- How do you handle polling in RTK Query? `pollingInterval`; here `pollUntil`.
- What about infinite scroll? A different store shape that appends pages; not implemented.

## Spring Security and OAuth

### Q78. Scenario: Secure service-to-service communication
**Type:** Scenario   **Level:** Advanced   **Topic:** Service identity, mTLS, OAuth client credentials

**Short answer.** Today order-service calls inventory-service with a short-lived SERVICE JWT minted from the shared HS256 secret, and `/api/v1/internal/**` requires the `SERVICE` role and is not routed by the gateway. That is demonstration-grade. Production should use per-service identity: OAuth 2.0 client credentials from an IdP, mTLS or a service mesh (SPIFFE identities), plus NetworkPolicies.

**Detailed answer.** What exists, verified in code:
- `ServiceTokenProvider` (`services/platform-common/.../common/security/ServiceTokenProvider.java`) issues a token with `sub = svc-<service>`, `roles = [SERVICE]`, default TTL 2 minutes (`OmsSecurityProperties.serviceTokenTtl`), cached and renewed 20 seconds before expiry.
- `OmsRestClientFactory.createServiceClient(baseUrl)` attaches `Authorization: Bearer` and the correlation id, with connect 2 s and read 3 s timeouts. Resilience4j retry (3 attempts, exponential backoff) and circuit breaker wrap `RestInventoryClient` (`@Retry(name = "inventory")`, `@CircuitBreaker(name = "inventory")`).
- Receiving side: `InternalController` is annotated `@PreAuthorize("hasRole('SERVICE')")`, and the default chain has `requestMatchers("/api/v1/internal/**").hasRole(Roles.SERVICE)`.
- Gateway: answers 404 for `/api/v1/internal/**` and rejects SERVICE tokens at the edge (403).
- Network: `infrastructure/kubernetes/base/networkpolicies.yaml` allows only the gateway to all services and only order-service to inventory-service on 18082 (authored, never applied to a cluster).

Honest weaknesses (from `docs/security/threat-model.md`, T10 and section 8): one shared secret across all services, so any service (or anyone with the secret) can mint a SERVICE token for any caller; `aud` is `oms-api` for everyone; JWTs cannot be revoked before expiry.

Production options, safest default first:
1. OAuth 2.0 client credentials: each service has its own client at the IdP, tokens signed with RS256 and verified by JWKS, with per-service scopes or audiences. Platform-common already switches to OIDC resource-server mode when `issuer-uri` or `jwk-set-uri` is set; what remains is replacing token minting with a client-credentials `OAuth2AuthorizedClientManager`-style client (design step, not implemented).
2. mTLS via a service mesh (Istio or Linkerd) or SPIFFE/SPIRE: workload identity at the transport layer, automatic certificate rotation. It authenticates the workload but carries no end-user roles.
3. Combine 1 and 2 plus NetworkPolicies: defence in depth.

**Trade-offs and failure modes.**
- Mesh adds operational complexity and sidecar resources.
- Client-credentials tokens need caching and refresh handling; an IdP outage affects internal calls.
- Do not forward the end-user token as a shortcut: it widens privilege and ties internal calls to user session lifetime.

**Likely follow-ups.**
- Why not trust the gateway? Any bypass of the gateway becomes privilege escalation; services re-validate.
- JWT or mTLS? They are complementary: mTLS for workload identity, JWT for caller and user context (ADR-0010).
- What would you change first? Remove the shared secret (RS256 per issuer) and add per-service audience.

### Q79. How does Spring Security 6 validate a JWT in this project?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** OAuth2 resource server

**Short answer.** Each servlet service configures `oauth2ResourceServer(jwt)` with a `NimbusJwtDecoder` and a composed validator: timestamp, issuer, and audience. A converter maps the `roles` claim to `ROLE_*` authorities, filtered to the four known roles, and unauthenticated or forbidden requests get RFC 7807 bodies.

**Detailed answer.** Code: `services/platform-common/src/main/java/com/enterprise/oms/common/autoconfigure/JwtSecurityAutoConfiguration.java`.

- Decoder (HS256 mode): `NimbusJwtDecoder.withSecretKey(new SecretKeySpec(bytes, "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build()`. The algorithm is pinned; the token's `alg` header does not choose it, which blocks `alg: none` and algorithm-confusion tricks.
- Validators:

```java
decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(jwt.getClockSkew()),
        issuerValidator, audience));
```

`clockSkew` defaults to 30 seconds; the audience validator requires `aud` to contain `oms-api`.
- Authorities: `authorities(Jwt, claim)` reads the `roles` claim (collection or space or comma separated string), uppercases it, keeps only names in `Roles.ALL` (CUSTOMER, OPERATOR, ADMIN, SERVICE), and prefixes `ROLE_`. Unknown roles are dropped, which fails closed.
- Chain: CSRF disabled (stateless bearer API, no cookies), `SessionCreationPolicy.STATELESS`, security headers (nosniff, frame deny, no-referrer, HSTS), `permitAll` only for health, info, prometheus and API docs, `/api/v1/internal/**` requires SERVICE, then `a.anyRequest().authenticated()`.
- Errors: `ProblemSecurityHandlers.EntryPoint` and `Denied` write problem+json through `ProblemWriter`; no stack traces.
- Method security: `@EnableMethodSecurity` enables `@PreAuthorize`; services also register `OmsAuthorizeRequestsCustomizer` beans for URL-level rules that run before request binding.
- Caller context: `CurrentUser.from(Authentication)` reads `sub`, roles, and a UUID `customerId` claim; a malformed claim is treated as absent.

Back-off: the chain is `@ConditionalOnMissingBean(SecurityFilterChain.class)`, so a service may define its own.

Spring Security 6 specifics: lambda DSL, `authorizeHttpRequests` with `requestMatchers` (not the removed `antMatchers`), and `@EnableMethodSecurity` (replacing `@EnableGlobalMethodSecurity`). Spring Boot 3.5.16 uses Spring Security 6.5.x (confirm in the dependency tree).

**Trade-offs and failure modes.**
- `permitAll` for `/swagger-ui/**` and `/v3/api-docs/**` is a known inherited default to restrict in production (threat model section 8).
- Clock skew too large extends token life; too small causes spurious 401s.
- Roles from an unvalidated claim name would be dangerous: filtering by `Roles.ALL` limits damage.

**Likely follow-ups.**
- Why validate `aud`? Prevent tokens issued for another API.
- `hasRole` or `hasAuthority`? `hasRole('X')` adds the `ROLE_` prefix.
- Why 401 versus 403? 401 for missing or invalid credentials, 403 for authenticated but not allowed.

### Q80. HS256 versus RS256 with JWKS: why is HS256 demonstration-only and how do you migrate?
**Type:** Conceptual   **Level:** Advanced   **Topic:** JWT signing, key rotation

**Short answer.** HS256 uses one shared secret for signing and verifying, so every service that can verify a token can also mint one. RS256 (or ES256) uses a private key held by the issuer and public keys published via JWKS, so resource servers can only verify. This repo uses HS256 for the demo and has a configuration switch to JWKS.

**Detailed answer.** Facts:
- Demo mode: secret from `OMS_JWT_SECRET`, at least 32 bytes, no default in prod; `JwtSecret.resolve` fails startup when absent or short; `oms.security.jwt.allow-ephemeral-secret=true` is for tests only (`docs/security/threat-model.md` section 9, `JwtSecretTest`).
- Switch: setting `oms.security.jwt.issuer-uri` and/or `jwk-set-uri` makes `JwtSecurityAutoConfiguration` build `NimbusJwtDecoder.withJwkSetUri(...)` (or `JwtDecoders.fromIssuerLocation`) and skips the HS256 beans (`JwtSecret`, `JwtTokenFactory`, `ServiceTokenProvider`).
- The gateway is reactive and has its own decoder config (`EdgeSecurityConfig`; `NimbusReactiveJwtDecoder`, `ReactiveJwtDecoders` are imported there).

Migration plan from the threat model (section 9):
1. Introduce an OIDC IdP; humans use authorization code with PKCE.
2. Set `issuer-uri` or `jwk-set-uri` on services; they validate RS256 via JWKS.
3. The gateway becomes a pure resource server; remove the dev token endpoint from production builds.
4. Replace SERVICE JWT minting by client credentials or mTLS.
5. Delete `OMS_JWT_SECRET` from `oms-secrets`.

Rotation: HS256 rotation needs a dual-key window. The threat model proposes `oms.security.jwt.previous-secret` but says it is not implemented; without it rotation means a rolling restart of every service and the gateway together and all outstanding tokens die. With JWKS, the IdP publishes the new key, keeps the old one for one token lifetime, and consumers refresh the JWKS cache; the `kid` header selects the key.

Production specifics: the JWKS endpoint must be reachable (egress policy), cached with a refresh strategy, and validated for `iss`; test the failure behaviour when the IdP is down: `JwtDecoders.fromIssuerLocation` performs OIDC discovery when the decoder is built (an unreachable IdP can fail startup), whereas `NimbusJwtDecoder.withJwkSetUri` fetches keys on first use.

**Trade-offs and failure modes.**
- RS256 verification costs more CPU than HMAC (usually negligible).
- JWKS endpoint outage prevents verification of tokens signed with an uncached new key.
- A leaked HS256 secret lets an attacker mint ADMIN tokens: rotate immediately and review `audit_events`.

**Likely follow-ups.**
- Why ES256 sometimes? Smaller signatures and keys.
- Where does `kid` matter? JWKS key selection during rotation.
- Can you revoke a JWT? Not before `exp` without a deny list (Q85).

### Q81. Scenario: A customer changes the id in the URL or body to see another customer's order. How is it prevented?
**Type:** Scenario   **Level:** Advanced   **Topic:** BOLA, authorization layers

**Short answer.** With layered checks: URL-level role rules, `@PreAuthorize` on controllers, and a resource-level ownership check using the token's `customerId`, never the request's. Another customer's order returns 404, not 403, so existence is not revealed, and list endpoints force the filter from the token.

**Detailed answer.** The class of bug is Broken Object Level Authorization (OWASP API Security Top 10). `docs/code-review/02-bola-customerId-from-body.md` shows a deliberately vulnerable pull request; the real implementation is in order-service.

Layers:
1. URL rules, before binding (`OrderSecurityConfig`): `registry.requestMatchers("/api/v1/orders", "/api/v1/orders/**").hasAnyRole(CUSTOMER, OPERATOR, ADMIN)`. An unauthorised caller gets 403 whatever the body, with no validation-error oracle.
2. Method rule: `@PreAuthorize(ROLES)` with `ROLES = "hasAnyRole('CUSTOMER','OPERATOR','ADMIN')"` in `OrderController`.
3. Ownership in services: `CurrentUser.canAccessCustomer(ownerCustomerId)` is true for staff or for a CUSTOMER whose token `customerId` equals the owner. `OrderQueryService` filters `o -> user.canAccessCustomer(o.getCustomerId())` and returns not found otherwise; `OrderCommandService` throws `ForbiddenException("customerId does not match the authenticated customer")` when a CUSTOMER posts an order for another id (403, per the contract), and for list endpoints the secure-coding guide prescribes forcing the filter from the token (`me.isCustomer() ? me.customerId() : requestedCustomerId`, illustrative snippet in `docs/security/secure-coding-guide.md`; the contract says a CUSTOMER asking for another `customerId` gets their own orders).
4. Idempotency keys are scoped to the caller's customer id, so keys cannot be probed across customers.

```java
public boolean canAccessCustomer(UUID ownerCustomerId) {
    return isStaff() || (isCustomer() && customerId != null && customerId.equals(ownerCustomerId));
}
```

Tests: negative tests per endpoint (401, 403, 404, 400, 413, 409) modeled on `SecurityApiTest`; the threat model lists T1 and T2 as unauthorized access and cross-customer exposure.

Why 404: contract section 4 ("to avoid enumeration"); UUIDs are not secrets and appear in emails and logs, so unguessability is not authorisation.

**Trade-offs and failure modes.**
- Returning 403 for foreign objects leaks existence; 404 hides it but complicates debugging (the audit trail helps).
- Each new endpoint must remember the ownership check; code review checklist and negative tests enforce it.
- Method-level checks that only test roles give a false sense of safety.

**Likely follow-ups.**
- Where do audit actors come from? From the security context (`CurrentUser`/`AuditService`), not the body.
- Why not rely on UUID secrecy? Identifiers leak.
- How do you test BOLA? Two customer tokens, cross-access every endpoint, expect 404 or 403 per contract.

### Q82. Which OAuth 2.0 and OIDC flows would the production design use, and what is the dev token endpoint?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** OAuth 2.0 grants, OIDC

**Short answer.** Humans in the SPA should use authorization code with PKCE (OAuth 2.1 direction), preferably through a backend-for-frontend; services should use client credentials. The repo's `POST /api/v1/auth/token` is not OAuth: it is a gateway dev-profile endpoint that exchanges demo credentials for an HS256 JWT.

**Detailed answer.** What exists: `gateway/.../auth/TokenController.java` with `LoginThrottle` and `DemoCredentials`, active only in profile `dev`; demo users `customer1`, `customer2`, `operator1`, `admin1`; passwords come from `OMS_DEMO_PASSWORD` (startup fails in `dev` when unset or shorter than 8 characters). The response shape is `{ accessToken, tokenType: "Bearer", expiresIn: 3600, username, roles, customerId }`. It resembles the old resource-owner password grant, which OAuth 2.1 removes; it exists purely for local demos, and the base ConfigMap deliberately has no `dev` profile.

Production flows:
- Authorization code with PKCE for browser or native clients: the code is exchanged at the token endpoint with a `code_verifier`, so an intercepted code is useless; implicit flow is obsolete.
- Client credentials for machine-to-machine (Q78).
- Refresh tokens: rotate and bind them; with a BFF they stay server-side.
- OIDC adds an ID token (identity), `userinfo`, discovery (`/.well-known/openid-configuration`) and JWKS. Access tokens are for APIs; ID tokens are for the client. Do not send ID tokens to APIs.
- Scopes versus roles: scopes express delegated permissions; the repo uses a `roles` claim configurable via `getRolesClaim()`.

Spring pieces: resource servers use `spring-security-oauth2-resource-server`; a BFF would use `oauth2Login()` with Spring Security's OAuth2 client, and the Spring Authorization Server project can act as an IdP, though production typically uses a managed IdP (Keycloak, Entra ID, Auth0, Cognito).

Honest status: no IdP is integrated; the OIDC switch and the migration plan exist, and the Angular UI has no PKCE implementation.

**Trade-offs and failure modes.**
- Tokens in the SPA are exposed to XSS; BFF cookies trade this for CSRF handling (SameSite, CSRF tokens).
- Longer access tokens are harder to revoke; shorter ones need refresh.
- Misconfigured redirect URIs enable code interception: use exact matching.

**Likely follow-ups.**
- Why PKCE for confidential clients? It still protects against code injection and is recommended for all.
- Access token or ID token to the API? Access token.
- What is `state`? CSRF protection for the authorization redirect.

### Q83. Scenario: The browser shows a CORS error calling the API from another origin. How does CORS relate to CSRF here?
**Type:** Scenario   **Level:** Intermediate   **Topic:** CORS, CSRF, same-origin deployment

**Short answer.** CORS is a browser rule about who may read cross-origin responses; CSRF is about a browser attaching ambient credentials (cookies) to forged requests. This API uses bearer tokens in the `Authorization` header, which browsers do not attach automatically, so CSRF protection is disabled; the UI is deployed same-origin so it needs no CORS at all.

**Detailed answer.** Facts:
- CSRF disabled: `csrf.disable()` in `JwtSecurityAutoConfiguration` and `EdgeSecurityConfig`, with the reason documented in code: stateless, no cookies or sessions, authentication solely by the `Authorization: Bearer` header. `docs/security/secure-coding-guide.md` section 4: "never add cookie-based auth without re-enabling CSRF".
- Same origin: nginx serves the UI and proxies `/api/` to the gateway, and in development `proxy.conf.json` forwards `/api` to port 18080. The contract says no CORS is needed.
- If a cross-origin UI is ever used: `OMS_GATEWAY_CORS_ALLOWED_ORIGINS` is an exact allow-list; `*` is rejected at startup; expose `Idempotent-Replay`, `Location`, `X-Correlation-Id`, `Retry-After` via `Access-Control-Expose-Headers`.

Debugging a CORS error:
1. Open the network tab: is the failure on the preflight `OPTIONS`? The edge chain does `permitAll` for `OPTIONS /**` so preflights succeed without a token.
2. Check `Access-Control-Allow-Origin` equals the exact origin, `Allow-Headers` includes `Authorization`, `Content-Type`, `Idempotency-Key`, `X-Correlation-Id`, and `Allow-Methods` covers the verb.
3. Check the gateway CORS config property; remember CORS is enforced by the browser, not the server, so curl will not reproduce it.
4. A proxy (nginx) must not strip headers.

Security point: never combine `Access-Control-Allow-Origin: *` with credentials; CORS is not an authentication mechanism and does not protect the API from non-browser clients.

Related hardening: security headers (nosniff, frame deny, no-referrer, HSTS), and nginx CSP `default-src 'self'; script-src 'self'; ... connect-src 'self'` (recommended; confirm before release).

**Trade-offs and failure modes.**
- Moving tokens to cookies (BFF) requires re-enabling CSRF protection.
- Wide origin lists increase risk; keep to exact origins.
- Preflight caching (`Access-Control-Max-Age`) reduces latency but delays config changes.

**Likely follow-ups.**
- Is CSRF irrelevant for bearer tokens? Largely, as long as tokens are not auto-attached; XSS remains the threat.
- Why `permitAll` for OPTIONS? Preflights carry no credentials.
- What does `SameSite` do? Limits cookie sending on cross-site requests; relevant for a BFF.

### Q84. Scenario: Where should the SPA keep its tokens, and what would you change for production?
**Type:** Scenario   **Level:** Advanced   **Topic:** Token storage, XSS, BFF

**Short answer.** The demo holds the token in a signal mirrored to `sessionStorage`; this is readable by any script on the origin, so an XSS bug exposes it. Production should use authorization code with PKCE through a BFF that keeps tokens server-side and gives the browser an HttpOnly, Secure, SameSite cookie, plus a strict CSP.

**Detailed answer.** Code: `frontend/oms-ui/src/app/core/auth/auth.store.ts`. The class comment states the trade-off. `sessionStorage` is per tab and cleared on tab close; `parseStoredSession` rejects malformed or expired data; an expiry `setTimeout` calls `logout('expired')`; `authInterceptor` never attaches the token to `/auth/token` or foreign origins. Never `localStorage`, never in URLs, never logged.

Options and honest comparison (safest default first):
1. BFF with HttpOnly cookie session (recommended for production): the browser never sees an access token, so XSS cannot exfiltrate it (an attacker with script execution can still make requests as the user while the page is open). Costs: stateful BFF or encrypted session cookie, CSRF protection must be re-enabled (SameSite=Lax or Strict plus CSRF token), and the BFF proxies API calls.
2. In-memory only access token with a refresh token in an HttpOnly cookie: reduces persistence; a reload requires a silent refresh.
3. `sessionStorage` (current, demo): simplest; XSS-readable; survives reload within a tab.
4. `localStorage`: persists across tabs and restarts; highest exposure; avoid.

Mitigations in the repo: Angular escapes interpolation by default; no `bypassSecurityTrust*`; nginx sets CSP with `script-src 'self'` and no inline scripts; `npm ci --ignore-scripts` and lockfile pinning in CI; Trivy and `npm audit` jobs are defined in the workflow but not run locally.

Also mention: short token lifetimes (the demo issues 3,600 seconds), `Referrer-Policy: no-referrer`, and that route guards are UX only (Q63).

Honest status: no PKCE or BFF is implemented; the ADR-0010 text says "production should use authorization-code with PKCE through a BFF".

**Trade-offs and failure modes.**
- BFF adds a component to run and secure, but removes token handling from JS.
- Cookies bring CSRF, logout and session fixation concerns.
- Strict CSP can break third-party widgets and needs testing; Angular Material injects styles, hence `style-src 'unsafe-inline'` in the current nginx config.

**Likely follow-ups.**
- Does HttpOnly stop XSS? It stops token theft, not actions performed by the injected script.
- Why not cookies for the demo? The API is stateless bearer-based; cookies would require CSRF.
- How do you detect XSS regressions? CSP reports, ESLint rules against `innerHTML`, review checklist.

### Q85. Scenario: A JWT may have been stolen. How do you revoke it, given JWTs are self-contained?
**Type:** Scenario   **Level:** Advanced   **Topic:** Token revocation

**Short answer.** A self-contained JWT is valid until `exp`, so revocation needs extra machinery. The repo relies on short lifetimes and acknowledges the gap (ADR-0010). For production, add a `jti` deny list, token introspection for sensitive operations, key rotation for mass revocation, and refresh-token revocation at the IdP.

**Detailed answer.** Facts from ADR-0010: "JWTs cannot be revoked before `exp` (mitigate with short lifetimes and, in production, introspection or a deny list keyed by `jti`)". Tokens contain `jti`, `iat`, `exp`. Demo user tokens last 3,600 seconds; SERVICE tokens 2 minutes.

Response plan:
1. Contain: if a single user is affected, disable the account at the IdP and revoke refresh tokens (so no new access tokens), and add the access token `jti` (or the `sub` plus issued-before timestamp) to a deny list consulted by the resource servers. In Spring, implement an extra `OAuth2TokenValidator<Jwt>` added to the `DelegatingOAuth2TokenValidator` in `omsJwtDecoder`; backed by Redis with TTL equal to the remaining token life, which fits the repo's optional Redis use. That validator is a design proposal, not implemented.
2. If the signing key is suspected: rotate the key. With HS256 today this means restarting all services with a new `OMS_JWT_SECRET`, which invalidates all tokens (threat model section 9, "Incident"). With JWKS, publish a new key and retire the old.
3. Investigate: query `audit_events` for ADMIN actions in the exposure window; correlate with `correlationId` in logs; open a post-incident review (`docs/governance/post-incident-review-template.md`).
4. Reduce future blast radius: shorter access tokens, step-up authentication for destructive admin actions, per-service audiences.

Alternatives: opaque tokens with introspection (RFC 7662) give instant revocation at the cost of a call per request or caching; Spring supports `opaqueToken()` on the resource server. Sender-constrained tokens (DPoP, mTLS-bound tokens) make stolen tokens useless to other clients.

Do not log tokens when investigating: `LogMasking.maskToken` and `JwtSecret.toString()` are designed to avoid leaks.

**Trade-offs and failure modes.**
- Deny lists add state and a lookup per request; TTL keeps them small.
- Introspection adds latency and an availability dependency.
- Very short tokens increase refresh traffic and complexity.

**Likely follow-ups.**
- Why `jti`? Unique identifier per token for deny-listing.
- Where does the SERVICE token fit? Two-minute TTL limits damage; still shared-secret minted.
- Can the gateway alone revoke? Only if every service honours the same list, since services re-validate.

### Q86. Why is the gateway security different from the service security (reactive versus servlet), and what does each layer enforce?
**Type:** Conceptual   **Level:** Advanced   **Topic:** WebFlux security, defense in depth

**Short answer.** Spring Cloud Gateway is reactive (WebFlux), so it uses `SecurityWebFilterChain` with `ServerHttpSecurity`, whereas the servlet services use `SecurityFilterChain` with `HttpSecurity`. Both validate the JWT; the gateway adds edge concerns (CORS, rate limiting, body size, correlation id) and coarse role rules as defense in depth, while services stay authoritative.

**Detailed answer.** Files: `gateway/src/main/java/com/enterprise/oms/gateway/security/EdgeSecurityConfig.java` (`@EnableWebFluxSecurity`, bean `edgeSecurityFilterChain`) and `platform-common`'s `JwtSecurityAutoConfiguration` (`@ConditionalOnWebApplication(type = SERVLET)`).

Gateway specifics:
- Reactive decoder (`ReactiveJwtDecoder`, `NimbusReactiveJwtDecoder`), a custom converter returning `Mono<AbstractAuthenticationToken>`, and `NoOpServerSecurityContextRepository` (stateless).
- Disabled: CSRF, HTTP Basic, form login, logout, request cache.
- Rules: `authorizeExchange` with `pathMatchers`; `/api/v1/internal/**` and `/fallback/**` are `denyAll`; `/api/v1/ops/**` requires OPERATOR or ADMIN; `/api/v1/auth/token` is permitted only in profile `dev`.
- Headers: CSP `default-src 'none'; frame-ancestors 'none'`, HSTS, frame deny, referrer policy, permissions policy.
- Extras: edge filters (`EdgeFilters`, `RequestSizeLimitWebFilter`, rate limiting via `RateLimitWebFilter` with in-memory buckets by default and Redis only when `oms.redis.enabled=true`).
- SERVICE tokens are rejected at the edge: services talk to each other directly.

Why services re-validate: ADR-0010 rejected "trust gateway headers"; a misrouted network policy would otherwise be total privilege escalation. The edge rules are explicitly "defense-in-depth only"; ownership checks (404 for another customer's data) can only happen where the data is.

Implementation gotchas documented in the contract (section 12 and 13): the gateway cannot pull servlet or JPA starters from `platform-common` and excludes `OmsCoreAutoConfiguration` and other `Oms*` autoconfigurations; it uses only `JwtSecret`, `JwtTokenFactory`, `LogMasking`, `CorrelationIds`.

**Trade-offs and failure modes.**
- Two security stacks (reactive and servlet) double the surface to keep consistent; shared properties and tests mitigate this.
- Duplicated role rules can drift; services are the source of truth.
- Blocking calls inside reactive security code stall the event loop.

**Likely follow-ups.**
- Why not only the gateway? One bypass equals full access.
- How are 401 and 403 rendered at the edge? As problem+json via `Problems.write`.
- What about WebFlux method security? `@EnableReactiveMethodSecurity` exists but this gateway uses path rules.

## Docker and Kubernetes

### Q87. Scenario: Deploy Spring Boot microservices to Kubernetes
**Type:** Scenario   **Level:** Advanced   **Topic:** Probes, config, secrets, rollout, HPA, rollback

**Short answer.** Package each service as a non-root image, deploy with a Deployment plus Service, PodDisruptionBudget and HPA, drive configuration from a ConfigMap and sensitive values from a Secret via `secretKeyRef`, gate traffic with startup, readiness and liveness probes, roll out with `maxUnavailable: 0`, and roll back with `kubectl rollout undo`. All of this exists as Kustomize manifests in `infrastructure/kubernetes`, validated statically (`kubeconform -strict`) but never applied to a cluster.

**Detailed answer.** Reference: `infrastructure/kubernetes/base/order-service.yaml`, `base/configmap.yaml`, `overlays/<env>/kustomization.yaml`, `infrastructure/kubernetes/README.md`.

- **Probes:** Spring Boot exposes `/actuator/health/liveness` and `/actuator/health/readiness`. `startupProbe` (period 5 s, `failureThreshold: 36`, three minutes) tolerates slow JVM and Flyway on first boot; `readinessProbe` (10 s, timeout 3 s) gates traffic; `livenessProbe` (20 s) restarts hung pods. Liveness must not depend on external systems, or a database blip restarts every pod.
- **Config:** `envFrom` a ConfigMap `oms-config` (`OMS_DB_HOST`, `KAFKA_BOOTSTRAP_SERVERS`, `SPRING_PROFILES_ACTIVE` and so on). The base profile is deliberately `json-logs` only; each overlay sets its own (`prod,json-logs` in prod) because `dev` enables the token endpoint and seed data.
- **Secrets:** `secretKeyRef` to `oms-secrets` (`jwt-secret`, `order-db-username|password`, `redis-password`). No overlay renders a Secret object; dev uses `examples/create-dev-secret.sh`, prod uses External Secrets Operator (Q93).
- **Rollout:** `strategy.rollingUpdate: { maxSurge: 1, maxUnavailable: 0 }`, `revisionHistoryLimit: 5`, `topologySpreadConstraints`, `terminationGracePeriodSeconds: 45`, `preStop: sleep 10` (Q89).
- **Hardening:** `runAsNonRoot` uid 10001, `readOnlyRootFilesystem`, `capabilities.drop: [ALL]`, `seccompProfile: RuntimeDefault`, `automountServiceAccountToken: false`, namespace labeled for Pod Security `restricted`; requests 250m / 512Mi, limits 1 CPU / 1Gi.
- **HPA:** `autoscaling/v2`, CPU 70 percent, 2 to 6 replicas (prod overlay 3 to 12), scale-down stabilisation 300 s.
- **Rollback:**

```bash
kubectl -n oms-<env> rollout status deploy/order-service --timeout=300s
kubectl -n oms-<env> rollout undo deploy/order-service
```

Rollback caveat from the README: the previous version must run on the new schema (expand/contract with Flyway), event schemas are additive, and an undo does not reverse business effects already processed.

What is not verified: probe behaviour, NetworkPolicies, PDB and HPA at runtime; prod endpoints are `PLACEHOLDER`s. Also `progressDeadlineSeconds` is not set (default 600 s), although the runbook recommends it.

**Trade-offs and failure modes.**
- CPU-based HPA reacts poorly to Kafka lag (Q90).
- A readiness probe depending on the database can drain all pods at once during a DB outage.
- `ScheduleAnyway` spread in base is soft; prod makes the hostname constraint `DoNotSchedule`.
- Kustomize has no release history; rollback relies on ReplicaSet history or Git.

**Likely follow-ups.**
- Liveness versus readiness? Liveness restarts, readiness removes from endpoints.
- Why a startup probe? To avoid liveness kills during slow starts.
- How do you handle a failed rollout? Check `rollout status`, events, previous logs, then undo (runbook 07).

### Q88. What makes a good Dockerfile for a Spring Boot service, and what is verified here?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** Container images

**Short answer.** Use layered jars so dependencies cache separately from application code, a pinned JRE base image, a non-root numeric user, JVM container-aware memory settings, and `exec` so the JVM receives SIGTERM. The repo's Dockerfiles do this; they are defined and built in CI, but never built on the author machine because Docker is not installed there.

**Detailed answer.** `infrastructure/docker/order-service.Dockerfile` (build context is the repository root; the fat jar must exist first):

```dockerfile
FROM ${JRE_IMAGE} AS extract
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted
...
USER 10001:10001
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
```

- Stage 1 extracts layers (`dependencies`, `spring-boot-loader`, `snapshot-dependencies`, `application`) with `-Djarmode=tools extract --layers`, the Spring Boot 3.3+ tool mode, so a code-only change rebuilds one small layer.
- Base image `eclipse-temurin:17.0.13_11-jre-alpine` is pinned to an exact build but not by digest; the file says to add the digest for production because tags are mutable.
- Non-root: group and user with fixed uid and gid 10001 so Kubernetes `runAsNonRoot` can verify them; `/tmp` writable (tmpfs in Compose, `emptyDir` in the pod) with `-Djava.io.tmpdir=/tmp`, because the root filesystem is read-only.
- JVM flags: `-XX:+UseContainerSupport`, `-XX:MaxRAMPercentage=75.0`, `-XX:InitialRAMPercentage=50.0`, `-XX:+ExitOnOutOfMemoryError` (let Kubernetes restart rather than limp), G1.
- Shutdown: `ENV SERVER_SHUTDOWN=graceful`, `SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE=25s`, `STOPSIGNAL SIGTERM`, and `exec` so the JVM is PID 1.
- `HEALTHCHECK` is for Docker or Compose only; Kubernetes ignores it.
- `.dockerignore` limits the context.
- UI: `frontend/oms-ui/Dockerfile` is a multi-stage Node build and `nginxinc/nginx-unprivileged` runtime on port 8080, user 101; its header says untested locally.
- Honest status: CI (`docker` and `docker-ui` jobs) builds, runs a Trivy image scan that fails on fixable HIGH/CRITICAL, and pushes to GHCR on main and tags; none of that was executed. There are no verified image digests or sizes to quote.

**Trade-offs and failure modes.**
- Alpine uses musl; native libraries or DNS behaviour can differ from glibc images.
- `MaxRAMPercentage` of 75 leaves 25 percent for metaspace, threads and off-heap; Kafka and Netty buffers can still exceed it, causing `OOMKilled`.
- Alternatives: Buildpacks (`spring-boot:build-image`), Jib; no Dockerfile needed but less control.

**Likely follow-ups.**
- Why layered jars? Better cache hits and smaller pushes.
- Why exec form and `exec`? Signals reach the JVM.
- Why pin by digest? A tag can be repointed; a digest cannot.

### Q89. Scenario: Users see 502 errors and failed Kafka messages during a rolling update. What do you check?
**Type:** Scenario   **Level:** Advanced   **Topic:** Graceful shutdown, readiness, rollout

**Short answer.** Requests are reaching pods that are starting or stopping. Verify readiness gating, `preStop` delay, graceful shutdown timeouts that fit inside `terminationGracePeriodSeconds`, and `maxUnavailable: 0`. Idempotent consumers and the outbox mean a pod killed mid-message causes redelivery, not data loss.

**Detailed answer.** The configuration in `base/order-service.yaml` and `docker/order-service.Dockerfile`:
- `maxSurge: 1, maxUnavailable: 0`: a new pod must be Ready before an old one is removed.
- Endpoint removal races SIGTERM: kube-proxy, ingress controllers and the gateway take seconds to drop an endpoint. `preStop: sleep 10` delays SIGTERM so the pod keeps serving while the removal propagates.
- Then SIGTERM triggers Spring graceful shutdown (`SERVER_SHUTDOWN=graceful`, 25 s per shutdown phase), which stops accepting new requests, drains in-flight ones and stops Kafka listeners.
- `terminationGracePeriodSeconds: 45` must exceed preStop (10) plus drain (25); the manifest comment says so. If it is too small, the kubelet sends SIGKILL mid-request.
- Readiness probe (period 10 s, 3 failures) means a pod becomes unready after up to 30 s of failures; for faster removal rely on preStop and shutdown.

Diagnose:
1. `kubectl rollout status` and `describe pod` events for `Killing` and probe failures.
2. Gateway side: connect failures and timeouts become 503 `UPSTREAM_UNAVAILABLE` with `Retry-After: 5`; upstream 4xx/5xx pass through unchanged. A 502 from ingress-nginx points to the pod closing connections; the ingress sets `proxy-read-timeout: 15`.
3. Metrics: `rate(http_server_requests_seconds_count{status=~"5.."}[5m])`, consumer lag, `oms_events_retries_total`, `oms_events_dead_lettered_total` (runbook 07).
4. Kafka: a rebalance happens whenever a consumer leaves; processing is idempotent (`processed_events` unique `(consumer, event_id)`), so redelivery is safe; check no DLT growth.

Also relevant: Flyway migrations at startup and `startupProbe` allowing three minutes; a slow migration holds locks (runbook 04).

Honest limit: this is the designed behaviour; no real rollout was observed because nothing was applied to a cluster.

**Trade-offs and failure modes.**
- Longer drain means slower rollouts and slower scale-down.
- Without `preStop`, in-flight requests fail with connection resets because traffic still arrives after SIGTERM.
- A PDB `minAvailable: 1` blocks node drains if replicas are unhealthy.

**Likely follow-ups.**
- Why `maxUnavailable: 0`? Capacity never drops below desired during rollout.
- Does the HPA interfere? Scale-down stabilisation 300 s avoids flapping during releases.
- What about long-lived connections? They must be closed by the app during shutdown; gateway timeouts are 10 s.

### Q90. Scenario: Kafka consumer lag is growing but CPU is low. How should the service scale?
**Type:** Scenario   **Level:** Advanced   **Topic:** HPA versus KEDA

**Short answer.** CPU-based HPA does not see lag. Use KEDA's Kafka scaler on consumer lag, remove the base HPA for that Deployment first, and cap replicas at the topic's partition count (3 here) because extra consumers sit idle. Also check whether the handler is slow because of the database, which more pods will not fix.

**Detailed answer.** Base: HPA `autoscaling/v2` on CPU 70 percent, 2 to 6 replicas (`base/order-service.yaml`). Example (not wired into any overlay): `infrastructure/kubernetes/keda/scaledobject-kafka-lag.yaml`:

```yaml
spec:
  scaleTargetRef:
    name: inventory-service
  minReplicaCount: 2
  maxReplicaCount: 3
  triggers:
    - type: kafka
      metadata:
        consumerGroup: inventory-service
        topic: oms.inventory.commands.v1
        lagThreshold: "50"
```

Reasoning:
- Each partition is consumed by at most one consumer in a group; topics have 3 partitions, so more than 3 replicas per consumer group achieves nothing, which is why `maxReplicaCount` is 3 (and why the base HPA maximum of 6 is wasteful for consumer-only workloads).
- KEDA creates its own HPA; having two HPAs on one Deployment conflicts, so patch the base HPA out (`$patch: delete`).
- `activationLagThreshold` controls the zero-to-one decision, relevant only when scaling from zero.
- Production needs `authenticationRef` for MSK SASL/TLS.

Diagnose before scaling: `GET /api/v1/ops/messaging` per service (lag, retries, dead-lettered), runbook `docs/runbooks/02-kafka-consumer-lag.md`, and Hikari saturation. Lag can come from contention on a hot row (`docs/database/slow-query-lock-contention-pool-exhaustion.md`), in which case adding consumers adds waiters. Also consider `oms.messaging.concurrency` (one thread per listener by default) as a cheaper lever than pods.

Rebalances: scaling changes the group membership and pauses consumption briefly; idempotent consumers make duplicates harmless.

Honest status: KEDA manifests are examples and unvalidated at runtime (CRD schemas were skipped by kubeconform).

**Trade-offs and failure modes.**
- Aggressive scale-up causes rebalance storms.
- Lag-based scaling can oscillate when messages arrive in bursts; use cooldown (`cooldownPeriod: 300`).
- More partitions raise the ceiling but cannot be reduced later and affect ordering only across keys (key is `orderId`).

**Likely follow-ups.**
- Why not scale on CPU? CPU can be flat while waiting on IO or locks.
- Can you exceed partitions? Not usefully, within one consumer group.
- What protects ordering? Key `orderId` keeps one order's events on one partition.

### Q91. Scenario: Lock down east-west traffic between services with NetworkPolicies and Pod Security
**Type:** Scenario   **Level:** Intermediate   **Topic:** NetworkPolicy, zero trust

**Short answer.** Apply a default-deny policy for ingress and egress in the namespace, then add explicit allows: DNS, ingress controller to the edge, gateway to services, order-service to inventory-service, services to data stores, and the monitoring namespace to metrics ports. This is authored in `base/networkpolicies.yaml`, never applied, and needs a CNI that enforces NetworkPolicy.

**Detailed answer.** `infrastructure/kubernetes/base/networkpolicies.yaml`:

```yaml
kind: NetworkPolicy
metadata:
  name: default-deny-all
spec:
  podSelector: {}
  policyTypes: [Ingress, Egress]
```

Then:
- `allow-dns-egress` to `kube-system` on 53 UDP and TCP (without this nothing resolves).
- `allow-ingress-controller-to-edge`: namespace `ingress-nginx` to pods with `component` in (`edge`, `ui`) on 18080 and 8080.
- `allow-gateway-and-order-to-services`: gateway to all four services; order-service to inventory-service on 18082 only (order validates products through REST).
- `allow-gateway-egress`, `allow-services-egress`: to Postgres 5432, Kafka (9092, plus MSK 9094, 9096, 9098) and Redis 6379.
- `allow-otlp-egress` to the `observability` namespace on 4317 and 4318; `allow-prometheus-scrape` from `monitoring`.
- Pod Security Admission: the `oms` namespace is labeled `pod-security.kubernetes.io/enforce: restricted` (and audit/warn), which the pod specs satisfy (non-root, seccomp, no capabilities, no privilege escalation).

Important behaviours: policies are additive allow-lists; they select pods by label, so label hygiene (`app.kubernetes.io/component`) is part of the security design; egress policies matter because a compromised pod otherwise reaches anything. NetworkPolicy works at L3/L4, so it cannot distinguish paths: `/api/v1/internal/**` protection still depends on the SERVICE role (Q78), and the gateway returns 404 for internal paths.

Honest weaknesses (documented): the managed-service `ipBlock` is the placeholder `10.0.0.0/8` and must be tightened to data subnets; nothing has been tested with real connectivity (`S11.2` pending in the threat model); the default AWS VPC CNI needs network policy support enabled.

**Trade-offs and failure modes.**
- Default deny breaks anything you forgot (DNS, metrics, OTLP); test in a non-production cluster first.
- Selectors on namespace names rely on the automatic `kubernetes.io/metadata.name` label.
- Policies do not encrypt traffic; mTLS or a mesh adds that.

**Likely follow-ups.**
- Does NetworkPolicy replace JWT validation? No, they are separate layers.
- How do you test? Run a debug pod and attempt connections that should be denied.
- What about egress to the IdP or JWKS? Add an explicit allow when OIDC mode is enabled.

## Cloud deployment (AWS)

### Q92. Scenario: Design the AWS deployment of this platform
**Type:** Scenario   **Level:** Advanced   **Topic:** EKS, RDS, MSK, ElastiCache, ALB

**Short answer.** Run the services on EKS in private subnets across three AZs, with RDS PostgreSQL 16 Multi-AZ, MSK for Kafka, ElastiCache for the optional Redis rate-limit store, ECR for images, an ALB with ACM and WAF in front, and Secrets Manager through IRSA. This exists as a Terraform skeleton and design in `infrastructure/cloud`; nothing was applied and no cost or performance numbers are claimed.

**Detailed answer.** Source: `infrastructure/cloud/README.md` and `infrastructure/cloud/aws/terraform/*.tf` (`network.tf`, `eks.tf`, `ecr.tf`, `data.tf`, `secrets_observability.tf`).

- **Network:** VPC with public, private and data subnets, NAT, route tables per tier.
- **EKS:** private subnets, `endpoint_private_access = true`, public access only if CIDRs are provided, KMS envelope encryption for secrets (`aws_kms_key` with rotation), audit logging enabled (`api`, `audit`, `authenticator`), managed node group, OIDC provider for IRSA.
- **ECR:** repository per service, `image_tag_mutability = "IMMUTABLE"`, scan on push, lifecycle keeping the last 30.
- **RDS:** PostgreSQL 16, `multi_az = true`, `storage_encrypted = true`, `manage_master_user_password = true` (RDS generates the secret in Secrets Manager), 14-day backups, `deletion_protection`, IAM database authentication enabled, Performance Insights on. One instance with four databases and roles (logical isolation).
- **MSK:** TLS in transit, IAM SASL, config `auto.create.topics.enable=false`, `default.replication.factor=3`, `min.insync.replicas=2`, `num.partitions=3`.
- **Redis:** ElastiCache replication group, Multi-AZ, at-rest and in-transit encryption; the app degrades to in-memory when `oms.redis.enabled=false`.
- **Ingress:** ALB through the AWS Load Balancer Controller with ACM and WAF; `/actuator` never routed. These resources are NOT in the skeleton (README lists what to add: controller, CNI network policy, EBS CSI, KEDA, External Secrets, kube-prometheus-stack, remote state, per-environment tfvars, guardrails).
- **Observability:** AMP and AMG or CloudWatch; the same dashboards and rules work with an AMP source.

Reasoning to state: the app is environment-driven, so only endpoints and auth (IAM or SASL) change per cloud (README section 4). The prod overlay carries placeholder endpoints (`oms-pg.cluster-PLACEHOLDER...`).

**Trade-offs and failure modes.**
- One shared RDS instance is cheaper and simpler but couples blast radius and noisy neighbours; separate instances later is mechanical because of per-service database and role (README trade-off).
- Self-managed versus managed Kafka: MSK removes broker operations but costs more and constrains versions.
- NAT gateways add hourly and data-processing cost; VPC endpoints reduce traffic through them.
- `terraform apply` was never run, so IAM, quotas and subnet sizing issues are likely on first use.

**Likely follow-ups.**
- Why private subnets? No direct internet exposure for workloads.
- How would you validate the Terraform? `terraform init -backend=false` and `validate` (needs provider download); `plan` needs credentials.
- Why four databases on one instance? Logical separation with cost control.

### Q93. How do IRSA, Secrets Manager and External Secrets keep static AWS keys and secrets out of the cluster and the repo?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** IAM Roles for Service Accounts, secrets management

**Short answer.** IRSA lets a Kubernetes ServiceAccount assume an IAM role through the cluster's OIDC provider, so pods get short-lived AWS credentials with no stored keys. External Secrets Operator (ESO) reads Secrets Manager using that role and materialises the Kubernetes Secret `oms-secrets`; the repo only holds secret key names.

**Detailed answer.** Terraform `eks.tf` creates `aws_iam_openid_connect_provider` from the cluster OIDC issuer and an IAM role `app` whose trust policy uses `sts:AssumeRoleWithWebIdentity` with the condition:

```hcl
condition {
  test     = "StringEquals"
  variable = "${replace(aws_iam_openid_connect_provider.eks.url, "https://", "")}:sub"
  values   = ["system:serviceaccount:oms-${var.environment}:oms-app"]
}
```

Only the ServiceAccount `oms-app` in namespace `oms-<env>` can assume it. The role policy allows `secretsmanager:GetSecretValue` and `DescribeSecret` on `arn:aws:secretsmanager:<region>:<account>:secret:oms/<env>/*` and MSK IAM actions (`kafka-cluster:Connect`, `ReadData`, `WriteData` and so on) scoped to the cluster, topic and group ARNs: least privilege, not `*`.

`infrastructure/kubernetes/examples/externalsecret.example.yaml` maps keys such as `jwt-secret` to `oms/prod/jwt` property `secret`, with `refreshInterval: 1h` and a `ClusterSecretStore` named `aws-secrets-manager` using IRSA. The base manifests use only `secretKeyRef` to `oms-secrets`, and `scripts/validate-infra.sh` checks that no overlay renders a Secret object. Terraform notes values never enter Terraform state: RDS generates the master password (`manage_master_user_password`), and the `redis` secret must be created without committing a value.

Operational points:
- Rotating a DB credential: ESO refresh plus a rolling restart, because the Hikari pool is built at startup.
- `automountServiceAccountToken: false` is set on pods; IRSA uses a projected web identity token injected by the EKS webhook for ServiceAccounts annotated with the role ARN, so confirm how the annotation and token projection interact with that setting before relying on it (not tested here).
- Enable EKS secrets encryption (done via KMS) and restrict `get secrets` RBAC.
- CI to AWS should use GitHub OIDC federation (`id-token: write` is already declared in `deploy-test`, unused by the placeholder), not long-lived access keys.

**Trade-offs and failure modes.**
- ESO becomes a dependency: if it or Secrets Manager is down, existing Secrets keep working but new pods with new values may not start.
- Kubernetes Secrets are base64, not encrypted by default; rely on KMS envelope encryption.
- Overbroad IAM trust (missing the `sub` condition) would let any ServiceAccount assume the role.

**Likely follow-ups.**
- IRSA versus EKS Pod Identity? Pod Identity is the newer AWS mechanism with simpler association; the repo uses IRSA.
- Sealed Secrets alternative? Safe to commit the sealed output, not the controller key.
- Where is the JWT secret? In Secrets Manager `oms/<env>/jwt`; it disappears once JWKS is adopted.

### Q94. Scenario: RDS Multi-AZ fails over during peak traffic. What happens to the services and what should you have configured?
**Type:** Scenario   **Level:** Advanced   **Topic:** RDS failover, connection pools

**Short answer.** On a Multi-AZ failover the endpoint's DNS switches to the standby (typically one to a few minutes) and existing connections drop. The services must reconnect: Hikari discards broken connections, callers retry via saga and Kafka backoff, and idempotent processing makes redelivery safe.

**Detailed answer.** What the repo defines: `data.tf` sets `multi_az = true`, backups 14 days, `deletion_protection`, `enabled_cloudwatch_logs_exports = ["postgresql"]`; failover mechanics are AWS behaviour (not tested here, no account was used).

Application side:
- Pool settings (`docs/database/slow-query-lock-contention-pool-exhaustion.md` section 8): `connection-timeout` 3000 ms fails fast; `max-lifetime` 25 minutes recycles connections; requests receive 5xx or 503 during the window rather than hanging.
- Writers in flight lose their transaction; because state change, history, audit and outbox row commit in one transaction, either all or none persisted. Kafka consumers retry with backoff (4 attempts, 500 ms times 2 up to 5 s), then dead-letter. Idempotent handlers (`processed_events` primary key `(consumer, event_id)`) make a replay a no-op.
- Outbox relay resumes: unpublished rows (`published_at IS NULL`, index `ix_outbox_pending`) are sent after recovery, at-least-once.
- Saga recovery: `SagaRecoveryJob` re-drives steps stuck longer than `oms.saga.stuck-after-seconds` (default 60); after max attempts the order goes to FAILED or `COMPENSATION_PENDING` flagged for admin retry or reconciliation.
- DNS caching: the JVM caches DNS lookups (`networkaddress.cache.ttl`); an excessively long TTL can keep resolving the old primary. This is a known risk; set a low TTL for RDS endpoints.

Preparation checklist: use the cluster endpoint (never an instance IP), JDBC `connectTimeout`/`socketTimeout` and TCP keepalives so half-open sockets die, alert on `hikaricp_connections_timeout_total` and pending, use RDS Proxy if connection storms after failover are a problem (adds cost and behaviour differences), and rehearse failover in a non-production environment.

Do not claim numbers: failover duration depends on engine, load and crash recovery.

**Trade-offs and failure modes.**
- Multi-AZ doubles instance cost and does not give read scaling (read replicas do, with replication lag).
- A migration holding long locks during failover complicates recovery.
- Retry storms after failover can saturate the new primary; backoff and the circuit breaker help.

**Likely follow-ups.**
- Multi-AZ versus read replica? HA versus read scaling.
- Why does idempotency matter here? Commands are redelivered after failed transactions.
- What about `Flyway` at startup during failover? It can fail startup; readiness keeps traffic away.

### Q95. Why MSK, and how do replication and min ISR relate to the delivery guarantees you claim?
**Type:** Conceptual   **Level:** Intermediate   **Topic:** Managed Kafka, durability

**Short answer.** MSK removes broker patching and replacement work and supports TLS and IAM authentication. With replication factor 3, `min.insync.replicas=2` and producers using `acks=all` plus idempotence, an acknowledged write survives one broker failure. The repo claims at-least-once delivery with an effectively-once business effect, not exactly-once.

**Detailed answer.** Terraform (`data.tf`): `aws_msk_cluster` with brokers equal to AZ count, `client_broker = "TLS"`, `in_cluster = true`, SASL IAM, and a configuration with `auto.create.topics.enable=false`, `default.replication.factor=3`, `min.insync.replicas=2`, `num.partitions=3`. Topics come from the topic job (`infrastructure/compose/kafka/create-topics.sh`, `dev-dependencies/files/create-topics.sh`), not auto-creation.

Guarantee chain (`docs/architecture/00-shared-contract.md` section 7, `delivery-semantics.md`):
1. Producer: transactional outbox row written in the same DB transaction as the state change; a relay publishes with `acks=all` and `enable.idempotence=true` (`omsKafkaTemplate`), then marks `published_at`. A crash between publish and mark causes a re-publish, so delivery is at-least-once.
2. Broker: with RF 3 and min ISR 2, a write with `acks=all` is acknowledged only when at least two replicas have it; with fewer in-sync replicas the producer receives `NotEnoughReplicas` errors rather than silently losing durability. Unclean leader election should stay disabled.
3. Consumer: `processed_events` unique `(consumer, event_id)` inserted in the same transaction as the business effect; duplicates are counted and ignored; record-level acks; `DefaultErrorHandler` with exponential backoff then `DeadLetterPublishingRecoverer` to `<topic>.DLT`; non-retryable errors (deserialization, validation) go straight to the DLT.
4. Ordering: key `orderId`, so one order's events stay in one partition.

Prod wiring: the ConfigMap prod patch points at an MSK bootstrap on port 9096 (SASL/SCRAM) while the IAM listener is 9098; the NetworkPolicy lists 9094, 9096 and 9098. The Terraform enables IAM only, so the bootstrap string and client config (SASL mechanism `AWS_MSK_IAM`) must be aligned: this is a placeholder inconsistency to verify when applying. Spring Kafka needs the `aws-msk-iam-auth` library on the classpath for IAM; it is not in this repo's verified dependencies.

**Trade-offs and failure modes.**
- Min ISR 2 with a broker down plus another failure halts writes; availability versus durability.
- MSK limits broker configuration compared with self-managed.
- Exactly-once transactions across Kafka and PostgreSQL are not provided; the outbox plus idempotent consumer replaces them.

**Likely follow-ups.**
- Why not publish directly in the request? A crash between commit and publish loses or fabricates events.
- What does idempotence guard? Producer retries creating duplicates within a session.
- DLT contents? Full payloads with `x-exception-*` headers; treat as sensitive.

### Q96. Scenario: Promote a release to production on AWS with immutable images and a rollback path
**Type:** Scenario   **Level:** Intermediate   **Topic:** CI/CD, ECR, rollback

**Short answer.** Build once, scan, tag with semver plus git SHA, deploy by digest, promote the same artifact through environments with an approval gate, and roll back with `kubectl rollout undo` or by re-applying the previous digest. In this repo CI builds and pushes to GHCR; ECR with immutable tags is defined in Terraform but no pipeline pushes to it, and the deploy job is a placeholder.

**Detailed answer.** Facts:
- `.github/workflows/ci.yml` (never executed; "authored offline"): jobs `version` (tag is `<VERSION>-<sha8>` or the git tag), `java-build-test`, `java-integration`, `frontend`, `codeql`, `semgrep`, `dependency-scan` (Trivy and `npm audit`), `secret-scan` (gitleaks), `infra-validate`, `docker`/`docker-ui` (build, Trivy image scan failing on fixable HIGH/CRITICAL, push to GHCR on main and `v*` tags), `smoke-local`, `deploy-test`.
- `deploy-test` is gated by `vars.DEPLOY_TEST_ENABLED`, `environment: test` (required reviewers), declares `id-token: write` for OIDC federation, and uses a placeholder kubeconfig secret; it runs `kustomize edit set image` and `kubectl rollout status`.
- Terraform `ecr.tf`: `IMMUTABLE` tags, scan on push, lifecycle keeps 30 images.
- Prod overlay comment: reference images by digest (`newName`/`digest` instead of `newTag`); the file currently uses `newTag: 1.0.0` placeholders.

Production design I would state:
1. CI authenticates to AWS with GitHub OIDC to a role limited to ECR push (no static keys), pushes to ECR, records the digest.
2. Image signing (cosign) and SBOM; pin GitHub Actions to commit SHAs and base images to digests (listed as "before production" in `secure-coding-guide.md` section 6).
3. Promote by committing the digest to the environment overlay (GitOps style: Argo CD or Flux deferred in ADR-0008); a human approves prod.
4. Verify: `kubectl rollout status`, dashboards (`OMS - API RED`, `OMS - Kafka Messaging`), post-deploy smoke test.
5. Rollback: `kubectl rollout undo` or revert the digest commit; schema changes expand/contract; event schemas additive; consumers deploy before producers.
6. Database migrations run at service start via Flyway; a destructive change must wait for the contract release.

Gaps to admit: no canary or progressive delivery, two CI definitions (`Jenkinsfile` mirror) can drift, Kustomize has no release history, and the first real deployment will probably reveal issues static checks cannot.

**Trade-offs and failure modes.**
- Immutable tags prevent overwriting but a bad image needs a new tag; roll forward or back by digest.
- Auto-deploy to prod without gates risks propagating a bad build; keep approvals.
- A rollback does not undo effects already processed by the new version.

**Likely follow-ups.**
- Why digest not tag? Tags can be repointed; digests identify exact bytes.
- Why OIDC to AWS? No long-lived keys to leak or rotate.
- When Helm or Argo CD? When release history, templating or continuous reconciliation justify it.



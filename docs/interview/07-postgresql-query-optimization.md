# PostgreSQL 16 Query Optimization: Indexes, EXPLAIN ANALYZE, MVCC, Locks, Pools and Flyway

This chapter is the database half of the interview. The platform uses PostgreSQL 16 in Compose, Kubernetes and the AWS reference, and H2 in `MODE=PostgreSQL` for local runs and most tests. Every service owns its own database (`order_db`, `inventory_db`, `fulfillment_db`, `notification_db`), and every schema is created by Flyway from SQL that has to run on both engines. The most instructive part is the inventory-service stock reservation path, because it combines an index design, a hot-row concurrency problem, a deadlock rule and a measured benchmark. Numbers quoted below are copied from `docs/database/inventory-explain-analyze.md` (captured on a laptop PostgreSQL 16.4 with synthetic data); this chapter does not add any new measurements.

## Big picture and kid-friendly analogy

Imagine a huge school library with 20,000 books and one very strict librarian.

* **An index** is the card catalogue at the front desk. Without it, to find "Gloves for kids" you walk down every shelf and read every spine (a sequential scan). With it, you look up the title, read "aisle 7, shelf 3" and walk straight there.
* **A composite index** is a catalogue sorted by two things at once: first by category, then by title inside the category. It is brilliant for "all science books, A to Z", useless for "all books whose title ends with 'dragon'", because the sort order does not help with that.
* **A partial index** is a small catalogue that only lists the books currently checked out. If you only ever ask "what is overdue?", you do not want to flip through cards for books sitting on the shelves.
* **EXPLAIN ANALYZE** is asking the librarian to show you the route she actually walked, with a stopwatch and a count of how many shelves she touched.
* **MVCC** (multi-version concurrency control) is the library keeping the old edition on the shelf while a new edition is being printed. Readers keep reading the old edition; nobody has to wait for the printing to finish.
* **A row lock** is a "being repaired" sign on one book. If two kids both want the last copy of a book, the second waits at the sign, and when the first is done the second looks again and sees "none left".
* **A deadlock** is two kids each holding one half of a two-volume set and each waiting for the other half. The fix is a rule everybody follows: always pick up volume 1 before volume 2.
* **A connection pool** is the 20 pencils in a jar. If 200 kids want to write at once and each kid holds a pencil while daydreaming, the other kids wait. Buying more pencils does not help if the real problem is kids daydreaming.

| Analogy | PostgreSQL concept |
|---|---|
| Card catalogue | B-tree index |
| Sorted by category then title | Composite index, leftmost-prefix rule |
| Catalogue of checked-out books only | Partial index (`CREATE INDEX ... WHERE ...`) |
| Librarian's route with stopwatch | `EXPLAIN (ANALYZE, BUFFERS)` |
| Old edition stays on the shelf | MVCC row versions and snapshots |
| "Being repaired" sign | Row lock (`FOR UPDATE`, `UPDATE`) |
| Always volume 1 before volume 2 | Consistent lock order (sorted `product_id`) |
| Pencil jar | HikariCP connection pool |

## Beginner tier

### Example 1: a B-tree index turns a 4,152-buffer scan into a 5-buffer scan

Inventory-service has a scheduler that releases reservations whose time-to-live has passed. The query is: "give me up to 100 reservations that are still `RESERVED` and whose `expires_at` is in the past, oldest first". The index that serves it is declared in the Flyway migration `services/inventory-service/src/main/resources/db/migration/V10__inventory_schema.sql`:

```sql
CREATE INDEX ix_reservations_status_expiry ON inventory_reservations (status, expires_at);
CREATE INDEX ix_reservations_created ON inventory_reservations (created_at);
```

The repository method that runs the query (`services/inventory-service/src/main/java/com/enterprise/oms/inventory/repo/ReservationRepository.java`):

```java
@Query("select r.id from Reservation r where r.status = com.enterprise.oms.inventory.domain.ReservationStatus.RESERVED "
        + "and r.expiresAt < :now order by r.expiresAt asc")
List<UUID> findExpiredIds(@Param("now") Instant now, Pageable pageable);
```

`docs/database/inventory-explain-analyze.md` section 3.2 captured the plan with 300,000 reservations of which 300 were overdue. With the index: `Index Scan using ix_reservations_status_expiry`, 5 shared buffers, `Execution Time: 0.223 ms`. With the index dropped inside a rolled-back transaction (DROP INDEX is transactional in PostgreSQL): `Parallel Seq Scan`, `Rows Removed by Filter: 99900` per worker, 4,152 buffers read, `Execution Time: 501.243 ms`. The lesson to say out loud: the index is not just "faster", it changes the algorithm from O(table size) to O(rows returned plus log n). Also notice why the index is `(status, expires_at)` and not just `expires_at`: the equality column goes first, the range column second, so PostgreSQL descends to the `RESERVED` section of the tree and then reads `expires_at` values in order, which also satisfies `ORDER BY expires_at` without a sort.

The beginner vocabulary you need: a B-tree keeps keys sorted, so it supports equality, ranges (`<`, `>`, `BETWEEN`), prefix `LIKE 'abc%'` (with the right collation or operator class) and `ORDER BY`. Primary keys and `UNIQUE` constraints create B-tree indexes automatically (`products_pkey`, `uq_products_sku`). Foreign keys do not: PostgreSQL does not index the referencing column for you. The repository handles this by making the lookup index double as the FK index (`uq_resitems_line (reservation_id, product_id)` covers `fk_resitems_reservation`).

### Example 2: reading a plan, and what an index cannot do

A plan is a tree. Read it from the innermost, most indented node outwards. Four things to look at first: the node type (`Index Scan`, `Bitmap Heap Scan`, `Seq Scan`), `Rows Removed by Filter` (work thrown away), estimated versus actual rows (a 10x gap means stale statistics, fix with `ANALYZE`), and `Buffers` (pages touched, the closest thing to a deterministic cost).

The catalog search is the repo's honest example of an index that does not help. `ProductQueryRepository` (`services/inventory-service/src/main/java/com/enterprise/oms/inventory/repo/ProductQueryRepository.java`) builds a "contains" filter:

```java
String pattern = like(f.q());
preds.add(cb.or(cb.like(cb.lower(p.get("name")), pattern, '\\'),
        cb.like(cb.lower(p.get("sku")), pattern, '\\')));
```

`like()` produces `%term%`. A B-tree cannot use a leading wildcard, and `lower(name)` is an expression, so `ix_products_name (name)` does not apply either. The captured plan (section 3.3 of the explain doc) is `Seq Scan on products p ... Rows Removed by Filter: 19798`, `Execution Time: 40.484 ms` for 20,000 products. The doc also records an experiment with the `pg_trgm` extension and GIN trigram indexes: the planner did not use them for this query shape, and the speedup to 4.3 ms came from better row estimates after `ANALYZE`, not from index use. The grown-up conclusion, which interviewers like, is to measure before adding an index, because a speculative index costs write time and disk and may never be chosen.

## Intermediate tier

### Example 1: composite index design, leftmost prefix, and the order-service listing

`services/order-service/src/main/resources/db/migration/V12__indexes.sql` documents its own reasoning in comments, which is a good habit to copy:

```sql
-- GET /orders is always "newest first" with optional filters, so every index ends in created_at and every filter
-- combination the API offers has a leading-column match:
--   customer view (forced customer filter)          -> ix_orders_customer_created   (customer_id, created_at)
--   customer + status                               -> ix_orders_customer_status_created
--   operator filter by status (+ date range)        -> ix_orders_status_created     (status, created_at)
--   operator date range only / default listing      -> ix_orders_created            (created_at)
CREATE INDEX ix_orders_customer_created        ON orders (customer_id, created_at);
CREATE INDEX ix_orders_customer_status_created ON orders (customer_id, status, created_at);
CREATE INDEX ix_orders_status_created          ON orders (status, created_at);
CREATE INDEX ix_orders_created                 ON orders (created_at);
```

The rule: a composite B-tree is usable for predicates on a leftmost prefix of its columns. `(customer_id, created_at)` serves `WHERE customer_id = ? ORDER BY created_at DESC LIMIT 20` with no sort, because within one customer the entries are already in time order. It does not serve `WHERE created_at > ?` alone (that is why `ix_orders_created` exists) and it only partly serves `WHERE status = ?`. The comment in the migration also says the ordering is `created_at DESC` served by a backward index scan, so no descending index variants are needed; PostgreSQL reads a B-tree in either direction. A descending or mixed-direction index (`created_at DESC, id ASC`) is needed only when a multi-column `ORDER BY` mixes directions.

The cost side: every extra index is another structure to update on each `INSERT` and on any `UPDATE` that changes an indexed column, and it prevents HOT (heap-only tuple) updates when the changed column is indexed. In this schema `orders.status` and `updated_at` change on every saga transition and both appear in indexes (`ix_orders_status_created`, `ix_orders_updated`), which is a deliberate trade for the operator "failed orders" view. Be ready to defend that trade rather than pretend indexes are free.

Partial indexes are the other half of this topic. The repo's migrations are intentionally portable (H2 cannot parse `CREATE INDEX ... WHERE`), so none of them is partial. Two places show where one would pay off. The outbox relay polls `WHERE published_at IS NULL ORDER BY created_at`, served today by the full index in `services/platform-common/src/main/resources/db/common/V1__outbox_processed_audit.sql`:

```sql
CREATE INDEX ix_outbox_pending ON outbox_events (published_at, created_at);
```

On PostgreSQL only, the production-shaped alternative (not in the repo) would be `CREATE INDEX ix_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;`. It stays tiny because published rows never enter it, and the query's `WHERE` must imply the index predicate for the planner to use it. The captured plan (section 3.5 of the explain doc) shows the existing index being used with `Execution Time: 0.105 ms` on 20,000 rows, so at that size the partial form is an optimisation to mention, not a bug to fix.

### Example 2: pagination, the count query, and N+1

`ProductQueryRepository.search` uses offset pagination (`setFirstResult`, `setMaxResults`) plus a separate `count`:

```java
orders.add(cb.asc(p.get("id")));   // stable paging
cq.orderBy(orders);
TypedQuery<Tuple> tq = em.createQuery(cq);
tq.setFirstResult((int) pageable.getOffset());
tq.setMaxResults(pageable.getPageSize());
```

Offset pagination is simple and lets the UI jump to page N, but `OFFSET 100000` makes PostgreSQL produce and discard 100,000 rows, so deep pages get linearly slower; it can also skip or repeat rows when data changes between page requests. Keyset (seek) pagination remembers the last row's sort key and asks for the next rows after it: `WHERE (created_at, id) < (:lastCreatedAt, :lastId) ORDER BY created_at DESC, id DESC LIMIT 20`. It costs one index seek regardless of depth, but it cannot jump to an arbitrary page and needs a unique tie-breaker (the `id` here). The platform contract (`docs/architecture/00-shared-contract.md` section 5) fixes offset paging with `size <= 100`, which bounds the worst case; keyset would be the upgrade for append-only streams such as `audit_events` or `stock_movements`.

The explain doc also flags the count: for the category page, the page query takes 1.240 ms but the `count(*)` takes 10.586 ms because it hash-joins all 20,000 `stock` rows, and it "grows linearly". Its recommendation is to drop the exact `totalElements` in favour of fetching `size + 1` rows ("has next page") if the count becomes the bottleneck.

N+1 is the application-side performance bug. `OrderRepository` (`services/order-service/src/main/java/com/enterprise/oms/order/repository/OrderRepository.java`) documents the fix:

```java
@Query("select distinct o from CustomerOrder o left join fetch o.items where o.id in :ids")
List<CustomerOrder> findAllWithItemsByIdIn(@Param("ids") Collection<UUID> ids);
```

Its class comment explains the rule: never combine a collection `join fetch` with paging, because Hibernate then pages in memory. Load the page of orders first (select plus count), then the items for exactly those ids in one more statement: three statements per page whatever the page size. `CustomerOrder.items` also carries `@BatchSize(size = 50)` and `application.yml` sets `default_batch_fetch_size: 50` and `open-in-view: false`, so a stray lazy access loads 50 parents' collections per query instead of one query per row. The review exercise behind this is `docs/code-review/06-unbounded-pagination-n-plus-one.md`.

## Advanced tier

### Example 1: MVCC, isolation levels, and why the atomic UPDATE cannot oversell

MVCC means an `UPDATE` never overwrites a row; it writes a new row version and marks the old one with the transaction id that replaced it. A statement (READ COMMITTED) or a transaction (REPEATABLE READ and SERIALIZABLE) sees a snapshot: the versions whose creating transaction committed before the snapshot and whose deleting transaction did not. Readers never block writers and writers never block readers; writers block only other writers of the same row. Old versions become dead tuples that `VACUUM` reclaims, which is why update-heavy tables such as `stock`, `inventory_reservations` and `outbox_events` deserve autovacuum attention (the slow-query doc lists them explicitly).

PostgreSQL's isolation levels, which are not the textbook four: READ UNCOMMITTED is accepted but behaves as READ COMMITTED. READ COMMITTED (the default, and what this repo uses: no `isolation =` attribute appears anywhere under `services/`) takes a fresh snapshot per statement, so two statements in one transaction can see different committed data. REPEATABLE READ is snapshot isolation: one snapshot for the whole transaction, with a serialization failure (SQLSTATE 40001) if you try to update a row another transaction changed after your snapshot. SERIALIZABLE adds predicate tracking (SSI) and may abort transactions with 40001 that you must retry. Phantom reads are already prevented at REPEATABLE READ in PostgreSQL.

The reservation core depends on a READ COMMITTED subtlety. The default strategy is `StockRepository.tryReserve` (`services/inventory-service/src/main/java/com/enterprise/oms/inventory/repo/StockRepository.java`):

```java
/** Strategy 1 (default): reserved += q iff available >= q. Returns 1 on success, 0 if insufficient. */
@Modifying(flushAutomatically = true)
@Query("update Stock s set s.reserved = s.reserved + :q, s.version = s.version + 1, s.updatedAt = :now "
        + "where s.productId = :id and s.onHand - s.reserved >= :q")
int tryReserve(@Param("id") UUID productId, @Param("q") int quantity, @Param("now") Instant now);
```

Under READ COMMITTED, when two transactions run this against the same row, the second blocks on the first's row lock; when the first commits, PostgreSQL re-evaluates the `WHERE` clause against the newest committed version of that row (the EvalPlanQual recheck) and either applies the update or finds zero matching rows. There is no window between "check stock" and "decrement stock" because both are one statement. `ADR-0006` (`docs/adr/0006-concurrency-control.md`) records the decision and rejects the read-then-write alternatives. The plan is trivial (captured in section 3.1): `Index Scan using stock_pkey` with `Filter: ((on_hand - reserved) >= 1)`; for a product with no stock the capture shows `Rows Removed by Filter: 1` and `Execution Time: 0.060 ms`, zero rows updated, which the code reads as "insufficient".

The database also defends itself. `V10__inventory_schema.sql` carries:

```sql
CONSTRAINT ck_stock_non_negative CHECK (on_hand >= 0 AND reserved >= 0),
CONSTRAINT ck_stock_reserved_le_on_hand CHECK (reserved <= on_hand)
```

If a future developer writes a buggy update, the constraint fails the transaction instead of silently overselling. Say this in an interview: application logic is the first line, a constraint is the last.

### Example 2: three locking strategies, SKIP LOCKED, and deadlocks with sorted lock order

The repo implements three strategies behind one interface and measured them. The pessimistic one uses `SELECT ... FOR UPDATE`:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select s from Stock s where s.productId = :id")
Optional<Stock> findForUpdate(@Param("id") UUID productId);
```

On PostgreSQL, Hibernate renders `PESSIMISTIC_WRITE` as `for no key update` (visible in the `pg_stat_statements` capture), a weaker lock than `FOR UPDATE` that still blocks other writers but does not block `INSERT`s of rows with foreign keys referencing the row. The optimistic strategy compares `version` in the `WHERE` clause (`StockRepository.compareAndReserve`) and counts lost races. Section 4 of the explain doc has the benchmark (16 threads, 150 calls per cell, same laptop, so compare ratios not absolutes). On the hot-row scenario: atomic 129 ops/s, pessimistic 164 ops/s, optimistic 64 ops/s with 2,389 lost compare-and-sets for 2,400 calls. On 200 spread-out products all three land between 798 and 827 ops/s. The `pg_stat_statements` capture shows the mean time per call at 59.5 ms for the optimistic update, 28.3 ms for `select ... for no key update` and 20.8 ms for the atomic update, statements that run in well under a millisecond when uncontended; the extra time is lock waiting, which `pg_stat_statements` charges to `total_exec_time`. The decision rule: optimistic locking suits rare conflicts (the admin stock edit in `StockService.adjust` uses it, with a bounded retry loop and `ConflictException` at the end); a hot row wants the database to serialize writers, which the atomic UPDATE does with the least code.

`FOR UPDATE SKIP LOCKED` is the third tool and the repo does not use it, which is itself a documented limitation. Semantics: rows currently locked by someone else are skipped instead of waited for, so N workers can each claim a different batch from a queue table without blocking each other (`SELECT ... WHERE published_at IS NULL ORDER BY created_at LIMIT 50 FOR UPDATE SKIP LOCKED`). `OutboxRelay` (`services/platform-common/src/main/java/com/enterprise/oms/common/outbox/OutboxRelay.java`) reads pending rows with a plain `findPending` and marks them in separate transactions, so two replicas can publish the same row. `services/order-service/README.md` section 13 says exactly this: it is safe because commands are idempotent and consumers de-duplicate through `processed_events`, but "before running many replicas, give the relay row-claiming (`FOR UPDATE SKIP LOCKED`)". Use `NOWAIT` instead when you prefer to fail immediately rather than skip.

Deadlocks happen when two transactions lock the same rows in opposite orders. A reservation for products A then B and another for B then A would deadlock on a hot pair. `ReservationService.normalize` (`services/inventory-service/src/main/java/com/enterprise/oms/inventory/service/ReservationService.java`) removes the possibility by merging duplicate lines into a `TreeMap<UUID, Integer>` so lines are processed in ascending `productId`:

```java
TreeMap<UUID, Integer> merged = new TreeMap<>();
for (QuantityItem i : items) {
    // ... null and range checks ...
    merged.merge(i.productId(), i.quantity(), Integer::sum);
}
```

`docs/tdd/inventory-reservation-tdd.md` ("Mutation check") records that with the sorting removed, `oppositeItemOrdersDoNotDeadlock` failed on all three strategies, with H2 reporting "chosen as a deadlock victim". PostgreSQL detects a deadlock after `deadlock_timeout` (default 1 s), aborts one transaction with SQLSTATE 40P01 and logs `deadlock detected`; the application must be prepared to retry. The same rule applies to any new code that touches several `stock` rows (batch tools, repair scripts), and the reservation row is claimed first and `stock` rows second, always in that order.

One more PostgreSQL-specific detail from the repo: `ReservationClaims` inserts the order's reservation slot with a plain `INSERT` inside a JDBC savepoint, not `INSERT ... ON CONFLICT DO NOTHING`. In PostgreSQL a failed statement aborts the entire transaction, so the savepoint lets the code catch `DuplicateKeyException`, roll back to the savepoint and carry on. `ON CONFLICT` would be the idiomatic PostgreSQL answer, but H2's PostgreSQL mode rejects it and the migrations and statements have to run on both.

## How this repository implements it

| Concept | Where in repo | Notes |
|---|---|---|
| Inventory schema, indexes, CHECK constraints | `services/inventory-service/src/main/resources/db/migration/V10__inventory_schema.sql` | `(status, expires_at)` expiry index, `uq_reservations_order` duplicate guard, stock CHECKs |
| Order listing indexes with rationale | `services/order-service/src/main/resources/db/migration/V12__indexes.sql` | every index ends in `created_at`; no partial or DESC indexes (portability) |
| Shared outbox, processed events, audit | `services/platform-common/src/main/resources/db/common/V1__outbox_processed_audit.sql` | `V1..V8` reserved for common; services start at `V10`; seeds `V1000+` |
| Atomic conditional UPDATE | `services/inventory-service/.../repo/StockRepository.java`, `.../reservation/AtomicUpdateReservationStrategy.java` | default strategy; zero affected rows means insufficient stock |
| Pessimistic and optimistic variants | `.../reservation/PessimisticLockReservationStrategy.java`, `.../reservation/OptimisticVersionReservationStrategy.java` | selected by `oms.inventory.reservation-strategy` |
| Sorted lock order | `.../service/ReservationService.java` (`normalize`) | `TreeMap` by `productId`; mutation-tested |
| Plans and benchmark evidence | `docs/database/inventory-explain-analyze.md` | PostgreSQL 16.4 captures plus H2 plans for comparison |
| Slow query, lock, pool runbook | `docs/database/slow-query-lock-contention-pool-exhaustion.md` | `pg_stat_activity`, `pg_blocking_pids`, `pg_stat_statements` recipes |
| HikariCP settings | `services/inventory-service/src/main/resources/application.yml`, `services/order-service/src/main/resources/application.yml` | inventory pool 20, order pool 10, `connection-timeout` 3000 ms |
| N+1 avoidance | `services/order-service/.../repository/OrderRepository.java`, `.../entity/CustomerOrder.java` | page, then items by `IN` list; `@BatchSize(50)` |
| Prod vs local datasource | `services/inventory-service/src/main/resources/application-prod.yml` | no seed (`classpath:db/common,classpath:db/migration`), no default credentials |

HikariCP is worth a paragraph of its own because pool exhaustion looks like a database problem and is usually an application problem. Inventory-service sets (`application.yml`):

```yaml
hikari:
  pool-name: inventory-hikari
  maximum-pool-size: ${OMS_DB_POOL_SIZE:20}
  minimum-idle: ${OMS_DB_POOL_MIN_IDLE:5}
  connection-timeout: ${OMS_DB_CONNECTION_TIMEOUT_MS:3000}   # fail fast (503-ish) instead of piling up request threads
  max-lifetime: 1500000                                      # < PG/pgbouncer server-side idle/lifetime limits
  leak-detection-threshold: ${OMS_DB_LEAK_DETECTION_MS:0}    # 0 = off; set 10000 while investigating leaks
```

The sizing argument in `docs/database/slow-query-lock-contention-pool-exhaustion.md` section 8 is Little's law: connections needed equals throughput times average hold time, so 400 reservations per second times 10 ms is about 4 busy connections on average and a pool of 20 is burst headroom. The famous HikariCP guidance is a small pool (roughly cores times 2 plus effective spindles, as a starting point), because the database saturates long before hundreds of connections do; and the total across instances plus other services must stay below PostgreSQL's `max_connections`. The decision rule in that doc: if `hikaricp_connections_active == max` and `pending > 0`, look at `hikaricp_connections_usage_seconds`; if hold time is high, connections are stuck behind locks or slow statements, so find the blocker with `pg_blocking_pids` instead of raising the pool. Order-service additionally sets `open-in-view: false` and keeps the REST call to inventory outside any transaction, so a connection is held only for the transaction, not for a network round trip.

Flyway conventions: migrations are versioned `V<n>__description.sql`, applied in order, recorded in `flyway_schema_history` with a checksum; editing an applied migration changes its checksum and fails validation, so you add a new version instead. In this repo `spring.jpa.hibernate.ddl-auto=none` (`services/platform-common/src/main/resources/META-INF/oms-defaults.properties`), so Flyway is the only schema authority. Never seed production: `application-prod.yml` drops `classpath:db/seed`. For safe rollbacks, `infrastructure/kubernetes/README.md` states the expand/contract rule: the previous application version must run against the new schema. In practice: add nullable columns first, deploy code that writes both, backfill, then drop in a later release; build large indexes with `CREATE INDEX CONCURRENTLY` (which cannot run inside a transaction block, so it needs its own migration with Flyway's non-transactional setting).

### Demonstration-only vs production-grade

Production-grade ideas present in the repo: constraints as a safety net, a documented index-per-query design, bounded paging (`size <= 100`, whitelisted sort fields), no N+1 on the listing path, fail-fast pool timeouts, sorted lock order with a test that fails without it, `ddl-auto=none` with Flyway as the single schema owner, and a runbook of real catalogue queries.

Demonstration-only or unverified: all plans were captured on a single Windows laptop with synthetic, uniformly distributed data; `docs/database/inventory-explain-analyze.md` section 6 is an unfilled template for staging and production plans, and says so. The default local database is H2, whose optimizer and lock behaviour differ (no `FOR NO KEY UPDATE`, lock waiting governed by `LOCK_TIMEOUT`), so H2 test results prove correctness of SQL shape, never performance. Testcontainers PostgreSQL tests are skipped on the dev machine because Docker is absent. The outbox relay lacks row claiming for multi-replica use. No partial, expression or covering (`INCLUDE`) indexes exist because migrations are kept portable. No `pg_trgm`, partitioning, read replica or PgBouncer is configured. `auto_explain`, `pg_stat_statements` and pool metrics on a live saturated pool were not captured. Offset paging remains for deep lists.

## Common mistakes and gotchas

1. **Indexing every column.** Each index slows writes and can block HOT updates. Add one per proven query, drop unused ones (`pg_stat_user_indexes.idx_scan = 0`).
2. **Wrong column order in a composite index.** Equality columns first, then range or sort columns. `(expires_at, status)` would not serve the expiry query as well as `(status, expires_at)`.
3. **Functions on indexed columns.** `WHERE lower(name) LIKE '%x%'` or `WHERE date(created_at) = ...` defeats a plain index; use an expression index or rewrite as a range.
4. **Trusting H2 plans.** H2 in PostgreSQL mode accepts most SQL but plans differently; a query "fast in tests" says nothing about PostgreSQL. Verify on real PostgreSQL.
5. **Read-then-write stock checks.** `SELECT` quantity, check in Java, `UPDATE` later is the classic oversell race (`docs/code-review/01-oversell-race.md`). Put the predicate in the `UPDATE`.
6. **Unordered multi-row locking.** Always lock in one global order (sorted ids). A deadlock in production is almost always an ordering bug.
7. **Fetch-join plus paging.** Hibernate warns `firstResult/maxResults specified with collection fetch; applying in memory`. Page ids first, fetch children second.
8. **`SELECT *` and entity loading for lists.** Project only needed columns for list endpoints.
9. **Fixing pool exhaustion by enlarging the pool.** More connections add more waiters on the same hot row and consume server backends.
10. **Long or idle-in-transaction sessions.** They hold row locks and block vacuum from cleaning dead tuples. Set `idle_in_transaction_session_timeout`, never call remote services inside a transaction.
11. **Editing an applied Flyway migration.** Checksum validation fails. Add a new version; keep reserved ranges (`V1..V8`, `V10+`, `V1000+`) clear.
12. **Using `ON CONFLICT` in portable SQL.** H2's PostgreSQL mode rejects it; the repo uses savepoints for that reason. Decide early whether H2 or Testcontainers is your test database.
13. **Misreading `EXPLAIN` without `ANALYZE`.** Plain `EXPLAIN` shows estimates only. `EXPLAIN ANALYZE` executes the statement, so wrap DML in `BEGIN ... ROLLBACK`.
14. **Forgetting statistics.** After a bulk load run `ANALYZE`; a bad row estimate (10x off) is a classic cause of a bad plan.

## Quick-recall cheat sheet

* B-tree: equality, range, prefix, sort. Leftmost-prefix rule for composites. Equality first, range second.
* Partial index: `CREATE INDEX ... WHERE`; the query predicate must imply it; PostgreSQL-only (not portable to H2).
* `EXPLAIN (ANALYZE, BUFFERS)`: look at node type, `Rows Removed by Filter`, estimate vs actual, buffers. Wrap DML in a rolled-back transaction.
* Repo evidence: expiry scan 0.223 ms and 5 buffers with the index, 501.243 ms and 4,152 buffers without. `%term%` search: sequential scan, 40.484 ms for 20,000 rows.
* MVCC: new row version per update, snapshots, readers never block writers, VACUUM reclaims dead tuples.
* Isolation: READ COMMITTED default (per-statement snapshot); REPEATABLE READ = snapshot isolation; SERIALIZABLE = SSI with retries on 40001. This repo runs the default.
* Atomic conditional UPDATE: `... WHERE on_hand - reserved >= :q`; 0 rows = insufficient; recheck after lock wait; CHECK constraints as the last line of defence.
* Strategy choice: hot row, atomic or pessimistic; optimistic only with spread-out rows (repo benchmark: 64 ops/s and 2,389 conflicts vs 129 and 164 ops/s on the hot row).
* `FOR UPDATE SKIP LOCKED`: queue-style claiming, not used in this repo (documented gap for the outbox relay); `NOWAIT` fails fast.
* Deadlock rule: lock in sorted `product_id` order; PostgreSQL error 40P01 after `deadlock_timeout`.
* Pagination: offset is simple but O(offset); keyset is O(1) per page but no random jump; drop exact counts if they dominate.
* N+1: page first, children by `IN`; `@BatchSize`/`default_batch_fetch_size`; `open-in-view: false`.
* HikariCP: small pool, `connection-timeout` fail fast, connections needed = throughput x hold time; high `usage_seconds` means blocked, not undersized.
* Flyway: versioned, checksummed, never edit applied migrations, expand/contract for rollback safety, `ddl-auto=none`.
* H2 PostgreSQL mode: same SQL, different optimizer and locks, no `ON CONFLICT`, no `FOR NO KEY UPDATE`; use it for correctness only.

# Inventory service: SQL, indexes and EXPLAIN ANALYZE

Everything under "Captured output" is **real output pasted verbatim** from runs made while building the service;
nothing is estimated or invented. Production plans were **not** captured (no production database exists): section 6 is an
explicitly labelled template for that.

## 1. What was captured, where, and what that means

| Source | Engine | Used for |
|---|---|---|
| **PostgreSQL 16.4** (Windows x86-64 build, 127.0.0.1, default tuning, `shared_buffers` 128 MB) | the real target database | all plans in sections 3 and 4, `pg_stat_statements` / `pg_locks` captures |
| H2 2.3.232 in `MODE=PostgreSQL` | the dev/test database | section 5 only |

How a PostgreSQL instance was obtained without Docker: the Maven cache of this machine already contained the
`io.zonky.test.postgres:embedded-postgres-binaries-windows-amd64:16.4.0` artifact (its SHA-1 matched the `.sha1` stored
next to it). Its `postgres-windows-x86_64.txz` was extracted into a fresh scratch directory and started with
`initdb` (scram auth, random generated password kept in a scratch file outside the repository) and `pg_ctl` on a local
port. The service's own Flyway migrations (`V1` + `V10`) created the schema; a synthetic data set was generated with
`generate_series`.

Data set (`pg_stat_user_tables`, after `ANALYZE`) and the PostgreSQL version:

```
>> SELECT version();
PostgreSQL 16.4, compiled by Visual C++ build 1940, 64-bit

>> SELECT name FROM pg_available_extensions WHERE name = 'pg_trgm';
pg_trgm

>> SELECT relname, n_live_tup FROM pg_stat_user_tables WHERE relname IN ('products','stock','inventory_reservations','inventory_reservation_items','stock_movements','outbox_events') ORDER BY relname;
inventory_reservation_items | 600000
inventory_reservations | 300000
outbox_events | 20000
products | 20000
stock | 20000
stock_movements | 500000
```

Caveats, please read before quoting numbers:

* one laptop, everything on a single machine, warm-ish caches (first executions show `read=` buffers, repeats are
  `hit=`): absolute times are **not** a capacity statement; compare plan **shapes** and relative cost.
* synthetic data: ~16 % of products are low or out of stock, `RESERVED` reservations are recent, categories are
  uniformly distributed. Skewed production data can change plans (noted where it matters).
* DML plans were captured inside `BEGIN ... ROLLBACK` so the data set stayed unchanged.
* H2 prints a different kind of plan (chosen index + `scanCount`, no costs, no timings): see section 5.

## 2. Indexes (migration `V10__inventory_schema.sql` + platform-common `V1`)

| Index | Columns | Serves |
|---|---|---|
| `products_pkey` / `uq_products_sku` | `id` / `sku` (unique) | lookups by id, duplicate-SKU protection, default catalog sort (`ORDER BY sku`) |
| `ix_products_category` | `(category, active)` | category filter (customers: `active = true`) |
| `ix_products_name` | `name` | prefix/ordered access by name (note: **not** usable for `LIKE '%x%'`) |
| `stock_pkey` | `product_id` | every reservation statement (atomic UPDATE, `FOR UPDATE`, snapshot) |
| `uq_reservations_order` | `order_id` (unique) | **duplicate-command protection** and "reservation of order X" |
| `ix_reservations_status_expiry` | `(status, expires_at)` | expiry scan (`status='RESERVED' AND expires_at < now()`) |
| `ix_reservations_created` | `created_at` | reservation listing, newest first |
| `uq_resitems_line` | `(reservation_id, product_id)` | items of a reservation, line uniqueness (doubles as the FK index) |
| `ix_resitems_product` | `product_id` | "which reservations hold product X" (reconciliation) |
| `ix_movements_product_time` | `(product_id, occurred_at)` | movement ledger per product, newest first |
| `ix_movements_reference` | `reference` | movements of an order / adjustment reference |
| `ix_outbox_pending` (common) | `(published_at, created_at)` | outbox relay: oldest unpublished rows |
| `ix_audit_entity`, `ix_audit_time` (common) | `(entity_type, entity_id, occurred_at)`, `(occurred_at)` | `/api/v1/ops/audit` |

Constraints that double as safety nets: `ck_stock_non_negative`, `ck_stock_reserved_le_on_hand` (the database itself
refuses an oversell even if application code were wrong), `ck_reservations_status`, `ck_resitems_qty`, FKs from stock,
items and movements.

## 3. The statements that matter, and their PostgreSQL plans

SQL below is what the application issues (Hibernate/JDBC); parameters are shown as literals in the captures.

### 3.1 Reservation hot path

**Atomic conditional UPDATE** (default strategy) - `StockRepository.tryReserve`. Affected rows = 1 reserved, 0 = insufficient.
Plan for a product with enough stock (2.8 ms includes first-touch buffer reads and dirtying) and for one without
(0.06 ms, `Rows Removed by Filter: 1`, **0 rows updated**):

```
>> EXPLAIN (ANALYZE, BUFFERS) UPDATE stock SET reserved = reserved + 1, version = version + 1, updated_at = now() WHERE product_id = md5('p100')::uuid AND on_hand - reserved >= 1;
Update on stock  (cost=0.29..8.32 rows=0 width=0) (actual time=1.134..1.135 rows=0 loops=1)
  Buffers: shared hit=16 read=5 dirtied=4
  ->  Index Scan using stock_pkey on stock  (cost=0.29..8.32 rows=1 width=26) (actual time=0.027..0.031 rows=1 loops=1)
        Index Cond: (product_id = '7f3ea87e-5de1-b525-ac97-2273c04c8456'::uuid)
        Filter: ((on_hand - reserved) >= 1)
        Buffers: shared hit=3
Planning Time: 0.173 ms
Execution Time: 2.836 ms
```

```
>> EXPLAIN (ANALYZE, BUFFERS) UPDATE stock SET reserved = reserved + 1, version = version + 1, updated_at = now() WHERE product_id = md5('p13')::uuid AND on_hand - reserved >= 1;
Update on stock  (cost=0.29..8.32 rows=0 width=0) (actual time=0.035..0.035 rows=0 loops=1)
  Buffers: shared hit=3
  ->  Index Scan using stock_pkey on stock  (cost=0.29..8.32 rows=1 width=26) (actual time=0.034..0.034 rows=0 loops=1)
        Index Cond: (product_id = 'f4fc7371-dbc6-aba6-7d64-fe6a3c76df2f'::uuid)
        Filter: ((on_hand - reserved) >= 1)
        Rows Removed by Filter: 1
        Buffers: shared hit=3
Planning Time: 0.172 ms
Execution Time: 0.060 ms
```

What to notice: a single primary-key index scan; the `on_hand - reserved >= q` predicate is applied as a **filter on the
locked row**. Under concurrency PostgreSQL (READ COMMITTED) waits for a competing writer's row lock and then
re-evaluates the predicate against the newest committed row version (the EvalPlanQual recheck), which is exactly why no
read-modify-write window exists.

**Pessimistic strategy** - `SELECT ... FOR UPDATE` (Hibernate on PostgreSQL renders `PESSIMISTIC_WRITE` as
`for no key update`, as seen in the `pg_stat_statements` capture in section 4):

```
>> EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM stock WHERE product_id = md5('p100')::uuid FOR UPDATE;
LockRows  (cost=0.29..8.31 rows=1 width=46) (actual time=0.165..0.174 rows=1 loops=1)
  Buffers: shared hit=6
  ->  Index Scan using stock_pkey on stock  (cost=0.29..8.30 rows=1 width=46) (actual time=0.133..0.141 rows=1 loops=1)
        Index Cond: (product_id = '7f3ea87e-5de1-b525-ac97-2273c04c8456'::uuid)
        Buffers: shared hit=5
Planning Time: 0.193 ms
Execution Time: 0.203 ms
```

**Duplicate protection / order lookup** - unique index `uq_reservations_order`:

```
>> EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM inventory_reservations WHERE order_id = md5('o1000')::uuid;
Index Scan using uq_reservations_order on inventory_reservations  (cost=0.42..8.44 rows=1 width=627) (actual time=0.139..0.141 rows=1 loops=1)
  Index Cond: (order_id = '171184ac-668f-e741-2b3b-2773a680d1d7'::uuid)
  Buffers: shared read=4
Planning:
  Buffers: shared hit=89 read=4
Planning Time: 2.693 ms
Execution Time: 0.166 ms
```

### 3.2 Expiry scan (scheduler, every `oms.inventory.expiry-scheduler-interval-ms`)

With `ix_reservations_status_expiry` (300 000 reservations, 300 of them overdue, `LIMIT 100`):

```
>> EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM inventory_reservations WHERE status = 'RESERVED' AND expires_at < now() ORDER BY expires_at ASC LIMIT 100;
Limit  (cost=0.42..8.45 rows=1 width=24) (actual time=0.136..0.194 rows=100 loops=1)
  Buffers: shared read=5
  ->  Index Scan using ix_reservations_status_expiry on inventory_reservations  (cost=0.42..8.45 rows=1 width=24) (actual time=0.134..0.182 rows=100 loops=1)
        Index Cond: (((status)::text = 'RESERVED'::text) AND (expires_at < now()))
        Buffers: shared read=5
Planning:
  Buffers: shared hit=11
Planning Time: 0.247 ms
Execution Time: 0.223 ms
```

The same query with the index dropped inside a rolled-back transaction (`DROP INDEX` is transactional in PostgreSQL):

```
>> DROP INDEX ix_reservations_status_expiry;
(0 rows)

>> EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM inventory_reservations WHERE status = 'RESERVED' AND expires_at < now() ORDER BY expires_at ASC LIMIT 100;
Limit  (cost=7342.61..7342.62 rows=1 width=24) (actual time=482.506..500.827 rows=100 loops=1)
  Buffers: shared hit=6 read=4152
  ->  Sort  (cost=7342.61..7342.62 rows=1 width=24) (actual time=482.504..500.815 rows=100 loops=1)
        Sort Key: expires_at
        Sort Method: top-N heapsort  Memory: 32kB
        Buffers: shared hit=6 read=4152
        ->  Gather  (cost=1000.00..7342.60 rows=1 width=24) (actual time=2.461..500.663 rows=300 loops=1)
              Workers Planned: 2
              Workers Launched: 2
              Buffers: shared hit=3 read=4152
              ->  Parallel Seq Scan on inventory_reservations  (cost=0.00..6342.50 rows=1 width=24) (actual time=0.012..42.444 rows=100 loops=3)
                    Filter: (((status)::text = 'RESERVED'::text) AND (expires_at < now()))
                    Rows Removed by Filter: 99900
                    Buffers: shared hit=3 read=4152
Planning:
  Buffers: shared hit=4
Planning Time: 0.802 ms
Execution Time: 501.243 ms
```

0.22 ms vs 501 ms and 5 vs 4 152 buffers: this is why the index is a composite `(status, expires_at)` and not just
`expires_at`.

### 3.3 Catalog reads

Customer product page, category filter (`LIMIT 20`) and its count query:

```
>> EXPLAIN (ANALYZE, BUFFERS) SELECT p.id, p.sku, p.name, p.price, s.on_hand, s.reserved FROM products p JOIN stock s ON s.product_id = p.id WHERE p.active = TRUE AND p.category = 'Category-7' ORDER BY p.sku ASC, p.id ASC LIMIT 20 OFFSET 0;
Limit  (cost=5.04..94.78 rows=20 width=53) (actual time=1.167..1.171 rows=20 loops=1)
  Buffers: shared hit=56 read=31
  ->  Incremental Sort  (cost=5.04..2203.79 rows=490 width=53) (actual time=1.165..1.167 rows=20 loops=1)
        Sort Key: p.sku, p.id
        Presorted Key: p.sku
        Full-sort Groups: 1  Sort Method: quicksort  Average Memory: 27kB  Peak Memory: 27kB
        Buffers: shared hit=56 read=31
        ->  Nested Loop  (cost=0.57..2181.74 rows=490 width=53) (actual time=0.129..1.059 rows=21 loops=1)
              Buffers: shared hit=50 read=31
              ->  Index Scan using uq_products_sku on products p  (cost=0.29..984.29 rows=490 width=45) (actual time=0.064..0.534 rows=21 loops=1)
                    Filter: (active AND ((category)::text = 'Category-7'::text))
                    Rows Removed by Filter: 786
                    Buffers: shared hit=13 read=5
              ->  Index Scan using stock_pkey on stock s  (cost=0.29..2.44 rows=1 width=24) (actual time=0.024..0.024 rows=1 loops=21)
                    Index Cond: (product_id = p.id)
                    Buffers: shared hit=37 read=26
Planning:
  Buffers: shared hit=151 read=9
Planning Time: 5.884 ms
Execution Time: 1.240 ms
```

```
>> EXPLAIN (ANALYZE, BUFFERS) SELECT count(p.id) FROM products p JOIN stock s ON s.product_id = p.id WHERE p.active = TRUE AND p.category = 'Category-7';
Aggregate  (cost=771.25..771.26 rows=1 width=8) (actual time=10.492..10.497 rows=1 loops=1)
  Buffers: shared hit=321 read=162
  ->  Hash Join  (cost=350.51..770.02 rows=490 width=16) (actual time=2.031..10.076 rows=500 loops=1)
        Hash Cond: (s.product_id = p.id)
        Buffers: shared hit=321 read=162
        ->  Seq Scan on stock s  (cost=0.00..367.00 rows=20000 width=16) (actual time=0.016..4.685 rows=20000 loops=1)
              Buffers: shared hit=7 read=160
        ->  Hash  (cost=344.39..344.39 rows=490 width=16) (actual time=1.997..2.000 rows=500 loops=1)
              Buckets: 1024  Batches: 1  Memory Usage: 32kB
              Buffers: shared hit=314 read=2
              ->  Bitmap Heap Scan on products p  (cost=9.31..344.39 rows=490 width=16) (actual time=0.189..1.845 rows=500 loops=1)
                    Recheck Cond: (((category)::text = 'Category-7'::text) AND active)
                    Heap Blocks: exact=314
                    Buffers: shared hit=314 read=2
                    ->  Bitmap Index Scan on ix_products_category  (cost=0.00..9.19 rows=490 width=0) (actual time=0.137..0.138 rows=500 loops=1)
                          Index Cond: (((category)::text = 'Category-7'::text) AND (active = true))
                          Buffers: shared read=2
Planning:
  Buffers: shared hit=14 read=1
Planning Time: 0.785 ms
Execution Time: 10.586 ms
```

Observations: for the page the planner walks `uq_products_sku` in sort order and filters (786 rows removed) instead of
using `ix_products_category`, because `LIMIT 20` makes the ordered walk cheaper; the **count** uses
`ix_products_category` (bitmap scan) but must hash-join all 20 000 stock rows: 10.6 ms here, and it grows linearly. If the
exact `totalElements` ever becomes expensive, drop it in favour of "has next page" (`size + 1` rows).

Text search (`q`): `lower(name) LIKE '%gloves%' OR lower(sku) LIKE '%gloves%'` cannot use a b-tree: **sequential scan of
`products`** (40 ms for 20 000 rows, O(n)):

```
>> EXPLAIN (ANALYZE, BUFFERS) SELECT p.id, p.sku, p.name, s.on_hand, s.reserved FROM products p JOIN stock s ON s.product_id = p.id WHERE p.active = TRUE AND (lower(p.name) LIKE '%gloves%' ESCAPE '\' OR lower(p.sku) LIKE '%gloves%' ESCAPE '\') ORDER BY p.sku ASC, p.id ASC LIMIT 20;
Limit  (cost=823.21..823.24 rows=13 width=48) (actual time=40.435..40.439 rows=20 loops=1)
  Buffers: shared hit=861 read=60
  ->  Sort  (cost=823.21..823.24 rows=13 width=48) (actual time=40.433..40.435 rows=20 loops=1)
        Sort Key: p.sku, p.id
        Sort Method: top-N heapsort  Memory: 27kB
        Buffers: shared hit=861 read=60
        ->  Nested Loop  (cost=0.29..822.97 rows=13 width=48) (actual time=0.575..39.962 rows=202 loops=1)
              Buffers: shared hit=861 read=60
              ->  Seq Scan on products p  (cost=0.00..715.00 rows=13 width=40) (actual time=0.514..36.936 rows=202 loops=1)
                    Filter: (active AND ((lower((name)::text) ~~ '%gloves%'::text) OR (lower((sku)::text) ~~ '%gloves%'::text)))
                    Rows Removed by Filter: 19798
                    Buffers: shared hit=315
              ->  Index Scan using stock_pkey on stock s  (cost=0.29..8.30 rows=1 width=24) (actual time=0.013..0.013 rows=1 loops=202)
                    Index Cond: (product_id = p.id)
                    Buffers: shared hit=546 read=60
Planning:
  Buffers: shared hit=12
Planning Time: 0.791 ms
Execution Time: 40.484 ms
```

A trigram experiment (extension `pg_trgm` is available in this build; created and rolled back in one transaction):

```
>> EXPLAIN (ANALYZE, BUFFERS) SELECT p.id, p.sku, p.name, s.on_hand, s.reserved FROM products p JOIN stock s ON s.product_id = p.id WHERE p.active = TRUE AND (lower(p.name) LIKE '%gloves%' ESCAPE '\' OR lower(p.sku) LIKE '%gloves%' ESCAPE '\') ORDER BY p.sku ASC, p.id ASC LIMIT 20;
Limit  (cost=10.98..218.71 rows=20 width=48) (actual time=4.238..4.242 rows=20 loops=1)
  Buffers: shared hit=100 read=4
  ->  Incremental Sort  (cost=10.98..2088.29 rows=200 width=48) (actual time=4.236..4.238 rows=20 loops=1)
        Sort Key: p.sku, p.id
        Presorted Key: p.sku
        Full-sort Groups: 1  Sort Method: quicksort  Average Memory: 27kB  Peak Memory: 27kB
        Buffers: shared hit=100 read=4
        ->  Nested Loop  (cost=0.57..2079.29 rows=200 width=48) (actual time=0.200..4.149 rows=21 loops=1)
              Buffers: shared hit=100 read=4
              ->  Index Scan using uq_products_sku on products p  (cost=0.29..1134.29 rows=200 width=40) (actual time=0.173..3.802 rows=21 loops=1)
                    Filter: (active AND ((lower((name)::text) ~~ '%gloves%'::text) OR (lower((sku)::text) ~~ '%gloves%'::text)))
                    Rows Removed by Filter: 2016
                    Buffers: shared hit=37 read=4
              ->  Index Scan using stock_pkey on stock s  (cost=0.29..4.72 rows=1 width=24) (actual time=0.014..0.014 rows=1 loops=21)
                    Index Cond: (product_id = p.id)
                    Buffers: shared hit=63
Planning:
  Buffers: shared hit=109
Planning Time: 3.264 ms
Execution Time: 4.323 ms

```

Honest reading: the GIN trigram indexes were **not used** for this query shape (the planner still walks
`uq_products_sku` and filters, which is cheap for `LIMIT 20` when ~1 % of rows match). The drop from 40 ms to 4.3 ms is
explained by the better row estimate (`rows=200` instead of 13) that `ANALYZE` produced from the *statistics of the
indexed expressions*, not by index use. Conclusion: do not add trigram indexes preemptively; revisit when the catalog
is large enough that the sequential scan hurts, and re-check with EXPLAIN on your data (rare patterns do benefit).

### 3.4 Inventory and reservation views (staff)

Reservation listing, newest first (`ix_reservations_created`, backward scan; with and without a status filter):

```
>> EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM inventory_reservations WHERE status = 'RESERVED' ORDER BY created_at DESC LIMIT 20 OFFSET 0;
Limit  (cost=0.42..166.13 rows=20 width=627) (actual time=0.093..0.103 rows=20 loops=1)
  Buffers: shared hit=1 read=3
  ->  Index Scan Backward using ix_reservations_created on inventory_reservations  (cost=0.42..15328.42 rows=1850 width=627) (actual time=0.091..0.098 rows=20 loops=1)
        Filter: ((status)::text = 'RESERVED'::text)
        Buffers: shared hit=1 read=3
Planning:
  Buffers: shared hit=24
Planning Time: 1.367 ms
Execution Time: 0.129 ms
```

```
>> EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM inventory_reservations ORDER BY created_at DESC LIMIT 20 OFFSET 0;
Limit  (cost=0.42..1.39 rows=20 width=627) (actual time=0.024..0.030 rows=20 loops=1)
  Buffers: shared hit=4
  ->  Index Scan Backward using ix_reservations_created on inventory_reservations  (cost=0.42..14578.42 rows=300000 width=627) (actual time=0.022..0.026 rows=20 loops=1)
        Buffers: shared hit=4
Planning Time: 0.183 ms
Execution Time: 0.051 ms
```

Caveat: the filtered variant is fast here because `RESERVED` rows are recent. If a status is rare **and old**, the
backward scan has to skip many rows; then a partial/composite index `(status, created_at DESC)` is the fix.

Items of a page of reservations (fetched in one query, no N+1) and the movement ledger of one product:

```
>> EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM inventory_reservation_items WHERE reservation_id IN (SELECT id FROM inventory_reservations ORDER BY created_at DESC LIMIT 20);
Nested Loop  (cost=2.07..251.44 rows=39 width=52) (actual time=0.127..0.902 rows=40 loops=1)
  Buffers: shared hit=68 read=36
  ->  HashAggregate  (cost=1.64..1.84 rows=20 width=16) (actual time=0.041..0.051 rows=20 loops=1)
        Group Key: inventory_reservations.id
        Batches: 1  Memory Usage: 24kB
        Buffers: shared hit=4
        ->  Limit  (cost=0.42..1.39 rows=20 width=24) (actual time=0.012..0.022 rows=20 loops=1)
              Buffers: shared hit=4
              ->  Index Scan Backward using ix_reservations_created on inventory_reservations  (cost=0.42..14578.42 rows=300000 width=24) (actual time=0.011..0.018 rows=20 loops=1)
                    Buffers: shared hit=4
  ->  Index Scan using uq_resitems_line on inventory_reservation_items  (cost=0.42..12.46 rows=2 width=52) (actual time=0.039..0.041 rows=2 loops=20)
        Index Cond: (reservation_id = inventory_reservations.id)
        Buffers: shared hit=64 read=36
Planning:
  Buffers: shared hit=57 read=8
Planning Time: 2.809 ms
Execution Time: 0.949 ms
```

```
>> EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM stock_movements WHERE product_id = md5('p100')::uuid ORDER BY occurred_at DESC LIMIT 20;
Limit  (cost=0.42..83.97 rows=20 width=113) (actual time=0.158..0.303 rows=20 loops=1)
  Buffers: shared hit=22 read=1
  ->  Index Scan Backward using ix_movements_product_time on stock_movements  (cost=0.42..104.86 rows=25 width=113) (actual time=0.156..0.298 rows=20 loops=1)
        Index Cond: (product_id = '7f3ea87e-5de1-b525-ac97-2273c04c8456'::uuid)
        Buffers: shared hit=22 read=1
Planning:
  Buffers: shared hit=101
Planning Time: 2.195 ms
Execution Time: 0.330 ms
```

Low-stock list (`available <= 10`): there is no index on the expression `on_hand - reserved`. An expression index was
created in a rolled-back transaction to see whether it would help:

```
>> EXPLAIN (ANALYZE, BUFFERS) SELECT p.id, p.sku, s.on_hand, s.reserved FROM products p JOIN stock s ON s.product_id = p.id WHERE s.on_hand - s.reserved <= 10 ORDER BY p.sku ASC LIMIT 20;
Limit  (cost=0.57..25.12 rows=20 width=35) (actual time=0.072..0.726 rows=20 loops=1)
  Buffers: shared hit=358 read=9
  ->  Nested Loop  (cost=0.57..8182.21 rows=6667 width=35) (actual time=0.070..0.721 rows=20 loops=1)
        Buffers: shared hit=358 read=9
        ->  Index Scan using uq_products_sku on products p  (cost=0.29..934.29 rows=20000 width=27) (actual time=0.011..0.054 rows=121 loops=1)
              Buffers: shared hit=4
        ->  Index Scan using stock_pkey on stock s  (cost=0.29..0.36 rows=1 width=24) (actual time=0.005..0.005 rows=0 loops=121)
              Index Cond: (product_id = p.id)
              Filter: ((on_hand - reserved) <= 10)
              Rows Removed by Filter: 1
              Buffers: shared hit=354 read=9
Planning:
  Buffers: shared hit=12
Planning Time: 0.378 ms
Execution Time: 0.770 ms
```

```
>> CREATE INDEX ix_stock_available ON stock ((on_hand - reserved));
(0 rows)

>> ANALYZE stock;
(0 rows)

>> EXPLAIN (ANALYZE, BUFFERS) SELECT p.id, p.sku, s.on_hand, s.reserved FROM products p JOIN stock s ON s.product_id = p.id WHERE s.on_hand - s.reserved <= 10 ORDER BY p.sku ASC LIMIT 20;
Limit  (cost=0.57..50.62 rows=20 width=35) (actual time=0.072..0.496 rows=20 loops=1)
  Buffers: shared hit=367
  ->  Nested Loop  (cost=0.57..8182.21 rows=3270 width=35) (actual time=0.071..0.492 rows=20 loops=1)
        Buffers: shared hit=367
        ->  Index Scan using uq_products_sku on products p  (cost=0.29..934.29 rows=20000 width=27) (actual time=0.010..0.042 rows=121 loops=1)
              Buffers: shared hit=4
        ->  Index Scan using stock_pkey on stock s  (cost=0.29..0.36 rows=1 width=24) (actual time=0.003..0.003 rows=0 loops=121)
              Index Cond: (product_id = p.id)
              Filter: ((on_hand - reserved) <= 10)
              Rows Removed by Filter: 1
              Buffers: shared hit=363
Planning:
  Buffers: shared hit=38 read=3
Planning Time: 0.643 ms
Execution Time: 0.533 ms
```

The planner ignored the expression index (identical plan): about 16 % of products qualify, so walking the SKU order and
filtering wins. It only pays off when low stock is rare (a few percent or less) or the page is deep.

### 3.5 Outbox relay poll

```
>> EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM outbox_events WHERE published_at IS NULL ORDER BY created_at ASC LIMIT 50;
Limit  (cost=76.55..76.65 rows=40 width=672) (actual time=0.067..0.073 rows=40 loops=1)
  Buffers: shared hit=3
  ->  Sort  (cost=76.55..76.65 rows=40 width=672) (actual time=0.066..0.068 rows=40 loops=1)
        Sort Key: created_at
        Sort Method: quicksort  Memory: 35kB
        Buffers: shared hit=3
        ->  Index Scan using ix_outbox_pending on outbox_events  (cost=0.29..75.49 rows=40 width=672) (actual time=0.023..0.031 rows=40 loops=1)
              Index Cond: (published_at IS NULL)
              Buffers: shared hit=3
Planning:
  Buffers: shared hit=87
Planning Time: 2.021 ms
Execution Time: 0.105 ms
```

## 4. Lock contention and statement statistics, captured

The three strategies were run through the PostgreSQL scenario suite (`InventoryLocalPostgresIT`, 50 threads vs one hot
product among others) with `pg_stat_statements` enabled (`shared_preload_libraries`, `track=all`) after
`pg_stat_statements_reset()`. Top statements by total time:

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

Reading it (the third column is the **mean** time per call): the same cheap primary-key statement (well under a millisecond when uncontended,
section 3.1) averages **59 ms** for the optimistic compare-and-set (`... and version = $4 ...`; 93 of its 131 calls updated a row, the others matched nothing:
a lost version race or no stock left), **28 ms** for `select ... for no key update`, and **21 ms** for the atomic
conditional UPDATE. The time is lock waiting on the hot row, not execution; `pg_stat_statements.total_exec_time`
includes it. Everything else on the reservation path (inserts of items, movements, outbox, audit) is 0.1-0.3 ms per call.

A deliberately reproduced row-lock wait on `stock` (one transaction holds an uncommitted `UPDATE`, a second one blocks),
observed from a third session. This is the investigation recipe of `slow-query-lock-contention-pool-exhaustion.md`:

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

Strategy micro-benchmark (whole `reserve()` transaction, 16 threads x 150 calls per cell, same machine, `-Doms.bench=true`):

```
Reservation strategy benchmark on PostgreSQL (16 threads x 150 reserve() calls per cell, 2026-10-18T13:00:40.973806300Z)
scenario                                 strategy          ops/s    p50 ms    p95 ms    max ms  conflicts
hot row: 1 product, 1 unit per order     atomic              129     45.50    230.94  10239.31          0
hot row: 1 product, 1 unit per order     pessimistic         164     40.85    209.28   3697.50          0
hot row: 1 product, 1 unit per order     optimistic           64    233.44    372.34   2521.01       2389
spread: 200 products, 1 unit per order   atomic              798      9.43     70.45    244.99          0
spread: 200 products, 1 unit per order   pessimistic         800      8.21     69.69    363.52          0
spread: 200 products, 1 unit per order   optimistic          827     10.04     48.95    265.63         72
3-line orders over 10 hot products       atomic              328     18.45     88.11   2567.45          0
3-line orders over 10 hot products       pessimistic         289     21.44    173.90    940.42          0
3-line orders over 10 hot products       optimistic          123     50.67    415.43    935.43       2303
```

and the same harness on H2 (in-memory, PostgreSQL mode):

```
Reservation strategy benchmark on H2 2.x PostgreSQL mode (in-memory) (16 threads x 150 reserve() calls per cell, 2026-10-18T12:56:53.668022400Z)
scenario                                 strategy          ops/s    p50 ms    p95 ms    max ms  conflicts
hot row: 1 product, 1 unit per order     atomic              120     34.01    467.88   3056.30          0
hot row: 1 product, 1 unit per order     pessimistic         182     17.07    369.44   1722.64          0
hot row: 1 product, 1 unit per order     optimistic           65     47.48   1038.98   7890.37       2085
spread: 200 products, 1 unit per order   atomic              411      4.34    169.34   1287.07          0
spread: 200 products, 1 unit per order   pessimistic         640      4.02     96.13    474.49          0
spread: 200 products, 1 unit per order   optimistic          555      3.74    112.18    576.73         93
3-line orders over 10 hot products       atomic              578      9.39     98.14    456.55          0
3-line orders over 10 hot products       pessimistic         562      9.88    107.74    464.67          0
3-line orders over 10 hot products       optimistic          160     31.73    305.22   3863.03       1845
```

Findings: with a **hot row** nothing beats the physics of one row lock (atomic ~ pessimistic; ~130-160 reservations/s here
because every reservation also writes items/movements/outbox/audit rows); the **optimistic** strategy burns most of its
attempts (2 389 lost compare-and-sets for 2 400 calls) and has the worst p50; with **spread-out** products all three are
equivalent (and optimistic is fine: 72 conflicts in 2 400 calls). Multi-line orders on few hot rows also punish the
optimistic strategy. The maximum latencies (seconds) are queueing behind the hot row on a laptop-class machine plus
connection-pool waits (pool 20, 16 threads), not a statement-level problem.

## 5. H2 (dev/test) plans, for comparison

H2's `EXPLAIN ANALYZE` reports the access path it chose and the number of rows it scanned (`scanCount`); it has no cost
model output and no timings, and its optimizer differs from PostgreSQL's. Data set: 20 000 products, 100 000
reservations (smaller than the PostgreSQL set). Use it to confirm that **the same indexes are used**, never for
performance conclusions.

```
>> EXPLAIN ANALYZE SELECT p.id, p.sku, p.name, p.price, s.on_hand, s.reserved FROM products p JOIN stock s ON s.product_id = p.id WHERE p.active = TRUE AND p.category = 'Category-7' ORDER BY p.sku ASC, p.id ASC LIMIT 20 OFFSET 0;
SELECT
    "p"."id",
    "p"."sku",
    "p"."name",
    "p"."price",
    "s"."on_hand",
    "s"."reserved"
FROM "public"."products" "p"
    /* public.ix_products_category: active = TRUE
        AND category = 'Category-7'
     */
    /* WHERE (p.active = TRUE)
        AND (p.category = 'Category-7')
    */
    /* scanCount: 501 */
INNER JOIN "public"."stock" "s"
    /* public.PRIMARY_KEY_6: product_id = p.id */
    ON 1=1
    /* scanCount: 1000 */
WHERE ("s"."product_id" = "p"."id")
    AND ("p"."active" = TRUE)
    AND ("p"."category" = 'Category-7')
ORDER BY 2, 1
OFFSET 0 ROWS
FETCH NEXT 20 ROWS ONLY
```

```
>> EXPLAIN ANALYZE UPDATE stock SET reserved = reserved + 1, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE product_id = (SELECT id FROM one_product) AND on_hand - reserved >= 1;
UPDATE "public"."stock"
    /* public.PRIMARY_KEY_6: product_id = (SELECT
        id
    FROM public.one_product
        /* public.one_product.tableScan */
        /* scanCount: 2 */)
     */
    /* scanCount: 2 */
SET
    "reserved" = "reserved" + 1,
    "version" = "version" + 1,
    "updated_at" = CURRENT_TIMESTAMP
WHERE (("on_hand" - "reserved") >= 1)
    AND ("product_id" = (SELECT
    "id"
FROM "public"."one_product"
    /* public.one_product.tableScan */
    /* scanCount: 2 */))
```

Expiry scan with the index and after dropping it (`scanCount` 151 vs 100 001, mirroring the 5 vs 4 152 buffers of PostgreSQL):

```
>> EXPLAIN ANALYZE SELECT id FROM inventory_reservations WHERE status = 'RESERVED' AND expires_at < CURRENT_TIMESTAMP ORDER BY expires_at ASC LIMIT 100;
SELECT
    "id"
FROM "public"."inventory_reservations"
    /* public.ix_reservations_status_expiry: status = 'RESERVED'
        AND expires_at < CURRENT_TIMESTAMP
     */
    /* scanCount: 151 */
WHERE ("status" = 'RESERVED')
    AND ("expires_at" < CURRENT_TIMESTAMP)
ORDER BY "expires_at"
FETCH FIRST 100 ROWS ONLY

>> DROP INDEX ix_reservations_status_expiry;
(0 rows)

>> EXPLAIN ANALYZE SELECT id FROM inventory_reservations WHERE status = 'RESERVED' AND expires_at < CURRENT_TIMESTAMP ORDER BY expires_at ASC LIMIT 100;
SELECT
    "id"
FROM "public"."inventory_reservations"
    /* public.inventory_reservations.tableScan */
    /* scanCount: 100001 */
WHERE ("status" = 'RESERVED')
    AND ("expires_at" < CURRENT_TIMESTAMP)
ORDER BY "expires_at"
FETCH FIRST 100 ROWS ONLY
```

```
>> EXPLAIN ANALYZE SELECT * FROM inventory_reservations ORDER BY created_at DESC LIMIT 20 OFFSET 0;
SELECT
    "public"."inventory_reservations"."id",
    "public"."inventory_reservations"."order_id",
    "public"."inventory_reservations"."status",
    "public"."inventory_reservations"."expires_at",
    "public"."inventory_reservations"."release_reason",
    "public"."inventory_reservations"."failure_reason",
    "public"."inventory_reservations"."failure_detail",
    "public"."inventory_reservations"."shipped_at",
    "public"."inventory_reservations"."created_at",
    "public"."inventory_reservations"."updated_at",
    "public"."inventory_reservations"."version"
FROM "public"."inventory_reservations"
    /* public.ix_reservations_created */
    /* scanCount: 20 */
ORDER BY 9 DESC
OFFSET 0 ROWS
FETCH NEXT 20 ROWS ONLY
/* index sorted */
```

Differences worth knowing: H2 uses `ix_products_category` for the page query where PostgreSQL walked `uq_products_sku`;
H2 has no `FOR NO KEY UPDATE`; its row-lock waiting is governed by `LOCK_TIMEOUT` (set to 20 s in the test URL), where
PostgreSQL has `lock_timeout` / `deadlock_timeout`.

## 6. TEMPLATE - capturing plans on a real environment (NOT executed here)

> **This section is a template. No staging or production plans exist in this repository.** Fill it from a restored copy
> or a read replica, never by experimenting on the primary.

```sql
-- 1. session setup (psql). Plans of DML must be rolled back.
SET statement_timeout = '30s';
SET lock_timeout = '2s';
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
UPDATE stock SET reserved = reserved + 1, version = version + 1, updated_at = now()
 WHERE product_id = '<real product uuid>' AND on_hand - reserved >= 1;
ROLLBACK;

-- 2. read paths
EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
SELECT id FROM inventory_reservations
 WHERE status = 'RESERVED' AND expires_at < now() ORDER BY expires_at LIMIT 100;
-- ... one block per statement of section 3, with real parameter values

-- 3. keep the evidence together with the facts that make it interpretable
SELECT version();
SELECT relname, n_live_tup, last_analyze, last_autoanalyze FROM pg_stat_user_tables WHERE relname LIKE 'inventory%' OR relname IN ('products','stock','stock_movements');
SHOW shared_buffers; SHOW work_mem; SHOW random_page_cost; SHOW max_connections;
```

| Field | Value |
|---|---|
| environment / date | _to fill_ |
| PostgreSQL version, instance class | _to fill_ |
| table sizes (rows, `pg_total_relation_size`) | _to fill_ |
| plan of each statement of section 3 | _to fill_ |
| differences versus section 3 and the explanation | _to fill_ |

Automate with `auto_explain` (`auto_explain.log_min_duration = '250ms'`, `log_analyze`, `log_buffers`) in staging so slow
plans are captured as they happen; investigation steps are in `docs/database/slow-query-lock-contention-pool-exhaustion.md`.

## 7. Reproduce the captures on your machine

1. Start any disposable PostgreSQL 16 (Docker `postgres:16-alpine` where available, or the zonky binaries as above).
2. Create an empty database and run the service tests against it:
   `OMS_TEST_PG_URL=jdbc:postgresql://127.0.0.1:5432/inventory_pg OMS_TEST_PG_USER=... OMS_TEST_PG_PASSWORD=... mvn -B -f services/pom.xml -pl inventory-service verify -Dit.test=InventoryLocalPostgresIT`
   (the database must be empty and disposable; Flyway creates the schema).
3. Load synthetic data with `generate_series` into the tables of section 2 (about 20k products, 300k reservations,
   600k items, 500k movements), `ANALYZE`, then run the statements of section 3 with `EXPLAIN (ANALYZE, BUFFERS)`.
4. Benchmark: `-Doms.bench=true` with `-Dtest=StrategyBenchmarkTest` (H2) or
   `-Dit.test=InventoryLocalPostgresIT#benchmarkStrategiesOnPostgres` (PostgreSQL).

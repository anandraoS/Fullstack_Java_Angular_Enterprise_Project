# Mentoring: Performance

Verified on disk: `PageRequestFactory`, `PageResponse`, `OutboxProperties`, `OutboxRelay`, `OmsRestClientFactory`, `HttpClientProperties`, `MessagingMetrics`. Service code exists now, but k6 (`performance/`) was never run (no k6 binary), so performance numbers below are **reasoning, not measurements**. Measure with `performance/` and say what hardware you used.

---

## 1. The lock you do not need

**PR:** reservation uses `SELECT ... FOR UPDATE` on each stock row, then Java arithmetic, then `save`.

```java
// sketch - BEFORE
Stock s = stockRepo.findForUpdate(productId);          // row lock held until commit
if (s.getOnHand() - s.getReserved() < qty) throw new InsufficientStockException(...);
s.setReserved(s.getReserved() + qty);
```

**Reviewer:** Correct, but it serialises every order for a hot SKU (think SKU-1001 with 500 in stock) for the whole transaction including the history and outbox inserts. The contract default is a single statement that checks and updates atomically:

```sql
UPDATE stock
   SET reserved = reserved + :q, version = version + 1
 WHERE product_id = :id AND on_hand - reserved >= :q
```

Affected rows 0 means insufficient. The row lock is held only for the statement, and the check cannot race because it is part of the write.

**Junior:** Then why keep the pessimistic variant?

**Reviewer:** When the decision needs more than one column or external data, a lock is simpler to reason about. It is documented and benchmarkable behind the `ReservationStrategy` seam (see `design-patterns.md`). Default atomic, benchmark both, decide with data.

**Junior:** And deadlocks with multi-line orders?

**Reviewer:** Always update in sorted productId order. Two orders [A,B] and [B,A] would otherwise deadlock. Add a test with opposing orders in two threads.

---

## 2. Transactions hold connections: do not call out inside

**PR:** `@Transactional` method calls inventory REST (read timeout 3 s, retries 3).

**Reviewer:** Worst case 3 attempts x (2 s connect + 3 s read) while holding a Hikari connection. With a pool of 10 and 10 slow validations, every other request waits `connectionTimeout` and fails. Symptoms are in `hikaricp.connections.pending` and `http.server.requests` p99, not in inventory's own metrics (runbook `../runbooks/04-database-latency-spikes.md`).

Rule: remote calls before opening the transaction, or after commit via the outbox.

---

## 3. Unbounded lists and N+1

**PR:**

```java
// sketch - BEFORE
@GetMapping("/orders")
List<OrderDto> list() {
    return orderRepo.findAll().stream().map(o -> {
        o.getItems().size();                         // lazy load per order
        return OrderDto.from(o, customerRepo.findById(o.getCustomerId()).get());   // query per order
    }).toList();
}
```

**Reviewer:** For 5,000 orders: 1 + 5,000 item queries + 5,000 customer queries. Plus no limit. The contract fixes paging (`size` default 20, max 100, rejected not clamped), which `PageRequestFactory` enforces.

Better:

```java
Page<OrderSummaryRow> page = orderRepo.search(filter, pageRequest);     // projection, joins customer name
// items fetched for the page only, one query:
Map<UUID, List<Item>> items = itemRepo.findByOrderIdIn(page.ids()).stream().collect(groupingBy(Item::orderId));
```

Verify by test: count SQL statements (Hibernate statistics or datasource-proxy) and assert `<= 3` for a page of 20. Full scenario: `../code-review/06-unbounded-pagination-n-plus-one.md`.

**Index review:** `orders(customer_id, created_at desc)`, `orders(status, updated_at)` for the failed-orders and stuck-saga queries, `saga_instances(state, updated_at)`, `outbox_events(published_at)` partial/filtered if the DB supports it (portable SQL: a plain index on `published_at`). Check `EXPLAIN` on PostgreSQL, since H2 plans differ.

---

## 4. Outbox relay throughput

**Verified:** `OutboxProperties`, `OutboxRelay`, and `OutboxService.pendingCount()` (cached, with `invalidatePendingCache()`).

**Reviewer questions to ask on any relay change:**

* Batch size and poll interval: what is the worst-case publish latency and the max throughput (`batch / interval`)?
* Does it publish synchronously per row (a network round trip each) or send a batch and await all futures?
* `acks=all` + idempotent producer is required (contract); do not trade it for speed.
* Does `oms.outbox.pending` rise under load and drain after? If it never drains, throughput < arrival rate.
* Old published rows: is there a purge job? An ever-growing outbox table slows the `WHERE published_at IS NULL` scan.

Ordering: key = `orderId` keeps per-order ordering within a partition. Do not publish one order's rows from parallel threads.

---

## 5. Consumer concurrency and poison pills

* 3 partitions per topic bound useful concurrency to 3 consumers per group.
* Retry backoff 500ms x2 up to 5 s, 4 attempts: a poison record delays its partition for roughly 0.5+1+2+... seconds, not forever. Non-retryable errors (deserialization, validation, `IllegalArgumentException`) must skip retries, otherwise one malformed record stalls the partition for no benefit.
* Large payloads: keep events small; ids and deltas, not whole aggregates.

## 6. Connection pools and timeouts (sizing sketch)

Little's law: concurrent DB connections needed = request rate x average time holding a connection. 200 req/s x 20 ms = 4 connections. A pool of 10 is plenty if nobody holds a connection during a 3 s REST call; a pool of 100 does not rescue you if they do. Watch `hikaricp.connections.active` vs `max` and `hikaricp.connections.pending`.

PromQL starters:

```
histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))
max(hikaricp_connections_pending)
rate(oms_events_processed_total[5m])
```

## 7. Caching

Redis is optional (product-read cache, rate limiting) and must degrade to in-memory when `oms.redis.enabled=false`. Never cache stock quantities used for reservation decisions; cache catalog data (name, price) with a short TTL and accept staleness, because the reservation SQL is the authority on stock.

## Checklist

- [ ] Hot-row updates use one conditional statement, sorted by key.
- [ ] No remote call inside a DB transaction.
- [ ] Every list endpoint is paged; query count asserted in a test.
- [ ] New query patterns have an index and an `EXPLAIN` on PostgreSQL.
- [ ] Outbox pending gauge drains under load.
- [ ] Claims have measurements or are labelled as reasoning.

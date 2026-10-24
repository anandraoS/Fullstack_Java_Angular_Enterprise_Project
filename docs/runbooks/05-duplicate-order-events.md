# Runbook 05 - Duplicate order events

All commands: illustrative, not executed against a live cluster.

Context: delivery is at-least-once (outbox relay, consumer retries, rebalances). Duplicates on the wire are **normal**; duplicate *business effects* are the bug. Consumers insert `(consumer, event_id)` into `processed_events` in the same transaction as the effect; the second delivery increments `oms.events.duplicates` and does nothing.

## Symptoms

* `oms.events.duplicates` spikes (usually benign, see below).
* Customer receives two emails for one event (notification effect duplicated), or two reservations/fulfillments for one order (serious).
* Two orders created for one customer action (HTTP-level duplicate: missing or reused Idempotency-Key handling).
* Order history shows the same transition twice.

## Metrics and PromQL

```
sum by (topic, type) (increase(oms_events_duplicates_total[15m]))
sum by (topic) (increase(oms_events_processed_total[15m]))
# duplicate ratio: benign if low and tied to a restart/rebalance window
sum(increase(oms_events_duplicates_total[15m])) / sum(increase(oms_events_processed_total[15m]))
increase(oms_events_retries_total[15m])
changes(kube_pod_container_status_restarts_total{namespace="oms"}[30m])
```

## Diagnostics

```bash
# What did the service see?
curl -s -H "Authorization: Bearer <JWT>" localhost:18080/api/v1/ops/notification/messaging | jq '{processedEvents,duplicatesIgnored,retries,deadLettered}'

# Is it the same eventId? Compare Kafka records for the order (key = orderId)
kafka-console-consumer.sh --bootstrap-server localhost:19092 --topic oms.order.events.v1 \
  --from-beginning --property print.key=true --property print.headers=true --timeout-ms 10000 | grep <orderId>
# Same eventId header twice => redelivery/relay re-publish (benign if effect once).
# Different eventIds for the same logical change => producer emitted two events (a bug upstream).

# Audit trail for the order
curl -s -H "Authorization: Bearer <JWT>" "localhost:18080/api/v1/ops/order/audit?entityType=Order&entityId=<orderId>" | jq
```

```sql
-- Was the event recorded as processed? (notification_db; columns per platform-common V1 migration)
select consumer, event_id, event_type, topic, processed_at from processed_events where event_id = '<eventId>';

-- Notification uniqueness guard: (source_event_id, template) must be unique
select source_event_id, template, count(*) from notifications group by 1,2 having count(*) > 1;

-- Outbox: was the same row published repeatedly?
select id, event_type, attempts, published_at, last_error from outbox_events where aggregate_id = '<orderId>' order by created_at;
```

HTTP-level duplicates: look for the same `Idempotency-Key` in the gateway/order logs; replays return 202 with `Idempotent-Replay: true`.

## Root-cause tree

```
Duplicate effect observed
|- eventId identical, effect applied twice
|    |- processed_events insert not in the same tx as the effect (self-invocation, missing @Transactional) -> code-review scenario 03
|    |- effect performed outside the processOnce callback (e.g. email "sent" before/after the tx)
|    |- consumer id (first argument of processOnce) differs between code paths -> dedupe key mismatch
|    '- non-idempotent external side effect (real email provider) -> needs provider idempotency key
|- eventId different, same logical change
|    |- producer built two events (retry in business code that re-appends to outbox)
|    '- eventId generated at relay time instead of at append time
|- Same eventId, effect once, duplicates counter up -> benign redelivery
|    |- consumer restart/rebalance replays uncommitted offsets
|    '- relay crashed after send, before marking published_at
'- Two HTTP-created orders
     |- client generated a new Idempotency-Key per retry
     '- key scope or request-hash comparison bug
```

## Mitigation

* Benign duplicates: no action; note the cause (deploy/rebalance) in the ticket.
* Duplicate notifications: they are simulated in this platform (no real provider is contacted); for a real provider, apologise per business policy and fix the dedupe.
* Duplicate fulfillment/reservation: reservations are guarded by a unique `order_id`; fulfillments should be too. Cancel the surplus via the audited paths (order cancel releases reservations), not by SQL.
* Producer bug: fix and deploy; no replay needed for the outbox (rows are not republished once `published_at` is set).
* Never delete rows from `processed_events` to "retry" an event: that re-enables duplicates for that id. To reprocess intentionally, use admin retry endpoints that create new, audited work.

## Prevention

* Idempotency test for every consumer (same event twice, effect once).
* `processed_events` insert and effect in one transaction, verified by rollback test.
* Unique constraints as the final safety net (reservations.order_id, notifications `(source_event_id, template)`).
* Retention on `processed_events` longer than the maximum redelivery window (topic retention).
* Clients must reuse the same Idempotency-Key when retrying the same request (documented in `docs/api/examples.md`).

## Practice exercise

1. Local stack (`scripts/run-local.sh`; stack start verified on 2026-10-18; the replay steps below were not run by hand, but the same behaviour is asserted by `NotificationKafkaTest`/`NotificationServiceTest`).
2. Create an order; copy the `OrderCreated` record from `oms.order.events.v1` (console consumer with headers).
3. Re-publish the same JSON (same `eventId`) twice to `oms.order.events.v1` with `kafka-console-producer.sh`. Expect `oms.events.duplicates` +2 and no new notification rows.
4. Change `eventId` in the payload (new UUID) and publish: a second notification is created or blocked by `(source_event_id, template)` uniqueness depending on design; record what you observe and explain why.
5. Replay the HTTP request with the same Idempotency-Key and body: 202 + `Idempotent-Replay: true`. Change the body: 422 `IDEMPOTENCY_KEY_REUSED`.

## Regression test pointers

* `services/platform-common/src/test/java/com/enterprise/oms/common/idempotency/IdempotentProcessorAndAuditTest.java` (exists: processOnce semantics).
* `.../messaging/ConsumerKafkaTest.java` (exists).
* Order creation idempotency matrix (exists): `IdempotencyApiTest` (11 tests, including replay, key reuse 422, missing key 400 and concurrent same-key submissions); live: 8 parallel requests with one key produced one order (`scripts/live-flows.mjs`). Teaching background: `docs/mentoring/testing.md` section 4.

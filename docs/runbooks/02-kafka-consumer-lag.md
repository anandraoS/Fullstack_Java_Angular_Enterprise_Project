# Runbook 02 - Kafka consumer lag

All commands: illustrative, not executed against a live cluster. `/api/v1/ops/messaging` is implemented in `services/platform-common` (`OpsMessagingController`, `MessagingStatusService`).

## Symptoms

* `consumerLag` in `GET /api/v1/ops/messaging` growing for a topic; `lagAvailable:false` means the AdminClient could not reach the broker (that itself is a finding, not an excuse).
* Downstream effects delayed: notifications late, reservations late, fulfillment late (see runbook 01).
* `oms.events.processed` rate falls to zero while producers still publish; `oms.outbox.pending` rises if the broker, not the consumer, is the problem.
* `oms.events.retries` increasing: consumers are failing and backing off (500 ms x2 up to 5 s, 4 attempts) before DLT.

## Metrics and PromQL

```
sum by (topic) (rate(oms_events_processed_total[5m]))
increase(oms_events_retries_total[10m])
increase(oms_events_dead_lettered_total[10m])
oms_outbox_pending
# Kafka client metric (names vary by exporter; example with Micrometer binder)
max by (topic, partition) (kafka_consumer_fetch_manager_records_lag{client_id=~".*order-service.*"})
histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri!~"/actuator.*"}[5m])))
```

## Diagnostics

```bash
# Lag per partition for a group (group id = service name)
kafka-consumer-groups.sh --bootstrap-server localhost:19092 --describe --group inventory-service
kafka-consumer-groups.sh --bootstrap-server localhost:19092 --list
# Look at: CURRENT-OFFSET vs LOG-END-OFFSET, LAG, CONSUMER-ID (empty = no active member), HOST

# Application view
curl -s -H "Authorization: Bearer <JWT>" localhost:18080/api/v1/ops/inventory/messaging | jq
#   -> topics[].consumerLag, committedOffsets, retries, deadLettered, outboxPending, lastProcessedAt, dltTopics

# Dead letters
kafka-console-consumer.sh --bootstrap-server localhost:19092 --topic oms.inventory.events.v1.DLT \
  --from-beginning --max-messages 5 --property print.key=true --property print.headers=true
kafka-run-class.sh kafka.tools.GetOffsetShell --bootstrap-server localhost:19092 --topic oms.inventory.events.v1.DLT

# Pod state and rebalances
kubectl -n oms get pods -l app=inventory-service
kubectl -n oms logs deploy/inventory-service --since=15m | grep -i -E 'rebalance|partitions (assigned|revoked)|CommitFailed|max.poll'
```

## Root-cause tree

```
Lag growing
|- LAG on all partitions, CONSUMER-ID empty -> no consumers
|    |- pods crash-looping / not ready -> runbook 06/07
|    '- listener container stopped after fatal error -> restart; fix cause
|- LAG on one partition only -> hot key (one orderId storm) or poison record at head
|    '- check retries metric and logs for same offset repeating
|- Consumers alive, processed rate low
|    |- handler slow: DB latency, row locks (runbook 03/04), remote call inside handler
|    |- repeated rebalances: processing exceeds max.poll.interval.ms -> consumer kicked
|    '- retry backoff on a failing record blocks that partition (up to ~8 s per record x attempts)
|- Producer side: oms.outbox.pending growing, consumers idle
|    |- relay not running (scheduling disabled / exception loop) -> app logs for OutboxRelay
|    '- broker unreachable / acks=all cannot be satisfied (min.insync.replicas) -> broker health
'- Poison pills landing in DLT -> lag drains but deadLettered rises; classify by x-exception-class
```

## Mitigation

1. Restore capacity: scale consumers up to the partition count (3). More than 3 per group is idle.

```bash
kubectl -n oms scale deploy/inventory-service --replicas=3
```

2. Fix the slow dependency (DB, lock contention) rather than raising poll intervals blindly.
3. Poison record: confirm it lands in the DLT (the error handler does this automatically after retries; non-retryable types go straight). Fix the producer or handler; replay valid records only after the fix. Replays are safe because consumers dedupe by `(consumer, event_id)` in `processed_events`.
4. Broker problems: check broker health and disk; the outbox buffers events safely (rows remain unpublished) and the relay resumes. Do not delete outbox rows.
5. Never reset offsets (`kafka-consumer-groups.sh --reset-offsets`) on a production group without ADMIN approval and a written reason: skipping events skips saga steps. If required, stop the consumers first, and use `--dry-run` before `--execute`.

## Prevention

* Alert on lag growth rate, not only absolute lag; alert on `oms_events_dead_lettered_total` increase and `oms_outbox_pending` > threshold for 5 min.
* Keep handlers short; no remote calls; bounded DB work.
* Non-retryable classification kept current (`KafkaConsumerConfigSupport.NON_RETRYABLE`).
* Load test with `performance/` (k6) and record consumer throughput per partition.

## Practice exercise

1. Local stack (`scripts/run-local.sh`, embedded Kafka on 19092; the stack start was verified on 2026-10-18, this lag exercise itself was not run).
2. Pause the inventory consumer by stopping inventory-service; post 20 orders (distinct Idempotency-Keys).
3. Run `kafka-consumer-groups.sh --describe --group inventory-service` and `ops/order/messaging`: observe lag on `oms.inventory.commands.v1`.
4. Restart inventory-service and watch lag drain; compute drain rate.
5. Poison test: publish `{not json` to `oms.inventory.commands.v1` with `kafka-console-producer.sh`; confirm the record appears in `oms.inventory.commands.v1.DLT` with `x-exception-class` and no retries.

Expected: lag grows then drains; the malformed record is dead-lettered once, `oms.events.dead_lettered` +1, `oms.events.retries` unchanged.

## Regression test pointers (exist)

* `services/platform-common/src/test/java/com/enterprise/oms/common/messaging/ConsumerKafkaTest.java` (retry, DLT, non-retryable behaviour on EmbeddedKafka).
* `services/platform-common/src/test/java/com/enterprise/oms/common/outbox/OutboxRelayKafkaTest.java` (relay publishes headers and key).

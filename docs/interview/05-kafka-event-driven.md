# Kafka and Event-Driven Design: Ordering, Delivery, Outbox, Idempotent Consumers and DLT

The services talk to each other through Kafka using Spring Kafka (`KafkaTemplate`, `@KafkaListener`, `DefaultErrorHandler`) on Spring Boot 3.5, with the clients' defaults unless noted. Compose and Kubernetes use `apache/kafka:3.9.x` in KRaft mode (no ZooKeeper); local runs use an embedded single-node KRaft broker on port 19092 (`services/local-stack`). The decisions are recorded in ADR-0002 (event design), ADR-0005 (idempotency) and ADR-0009 (transactional outbox), with the honest guarantees in `docs/architecture/delivery-semantics.md`.

## Big picture and kid-friendly analogy

Imagine a school mailroom.

* A **topic** is a labelled shelf, for example "Inventory news". Each shelf has **three numbered mail slots** (partitions). Letters are never taken out of a slot; they just stay in the order they arrived, and each reader keeps a **bookmark** (offset) saying how far they have read.
* Every letter has the **student's name** written on it (the key, here the order id). The mailroom has one rule: letters for the same student always go in the same slot. So one student's letters stay in order, but letters for different students in different slots can be read in any order.
* A **classroom** (consumer group) shares the work: each teacher takes whole slots. Three slots means at most three teachers have anything to do; a fourth sits idle. If a teacher goes home sick, the class **rebalances** and another teacher takes over their slot starting from the last bookmark.
* The **dead-letter box** is where letters that nobody can understand are put, with a sticky note saying why, so one weird letter does not block the whole slot forever.
* The **outbox** is a diary on your own desk. Before you walk to the mailroom you write the letter in your diary at the same moment as you finish your homework. A helper (the relay) then carries diary entries to the mailroom. If the helper trips and carries the same entry twice, the reader has a rule: "I already did that one, skip it" (idempotent consumer).

| Analogy | Kafka concept |
|---|---|
| Shelf with 3 slots | Topic with 3 partitions |
| Student's name on the letter | Record key (`orderId`) |
| Bookmark | Consumer offset (committed per group and partition) |
| Classroom | Consumer group (id = service name) |
| Teacher falls sick | Rebalance |
| Dead-letter box with sticky note | `<topic>.DLT` with `x-exception-*` headers |
| Desk diary plus helper | Transactional outbox plus relay |
| "I already did that one" | `processed_events` unique constraint |

## Beginner tier

### Example 1: topics, partitions and keys

The five business topics and their dead-letter twins are defined once (`services/platform-common/src/main/java/com/enterprise/oms/common/events/Topics.java`):

```java
public static final String ORDER_EVENTS = "oms.order.events.v1";
public static final String INVENTORY_COMMANDS = "oms.inventory.commands.v1";
public static final String INVENTORY_EVENTS = "oms.inventory.events.v1";
public static final String FULFILLMENT_COMMANDS = "oms.fulfillment.commands.v1";
public static final String FULFILLMENT_EVENTS = "oms.fulfillment.events.v1";

public static final String DLT_SUFFIX = ".DLT";
public static final int PARTITIONS = 3;
```

| Topic | Producer | Consumers (group = service name) |
|---|---|---|
| `oms.order.events.v1` | order-service | notification-service, inventory-service (only `OrderConfirmed`, `OrderCancelled`) |
| `oms.inventory.commands.v1` | order-service | inventory-service |
| `oms.inventory.events.v1` | inventory-service | order-service |
| `oms.fulfillment.commands.v1` | order-service | fulfillment-service |
| `oms.fulfillment.events.v1` | fulfillment-service | order-service, inventory-service, notification-service |

Design rules from ADR-0002: one owning producer per topic, commands and events on separate topics (different consumers, ACLs and retention), several event types per topic discriminated by `eventType`, naming `oms.<domain>.<events|commands>.v<major>`, and the **key is always the `orderId`**, set by the outbox as `envelope.aggregateId()`. The key picks the partition (`hash(key) mod partitions`), so all events for one order are strictly ordered. Topics are created idempotently by `infrastructure/compose/kafka/create-topics.sh`:

```sh
"$KT" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "$t" \
      --partitions "$PARTITIONS" --replication-factor "$REPLICATION" \
      --config retention.ms=604800000 --config min.insync.replicas=1
```

Seven days of retention for business topics (a record you want to replay must still exist) and 30 days (`retention.ms=2592000000`) for `.DLT` topics, whose partition count matches the source because the recoverer keeps the partitioner-chosen partition.

### Example 2: the event envelope and headers

Every record is a JSON envelope (`EventEnvelope<T>`, `services/platform-common/src/main/java/com/enterprise/oms/common/events/EventEnvelope.java`) with `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `correlationId`, `causationId`, `aggregateType`, `aggregateId`, `producer` and `payload`. The outbox also copies `correlationId`, `eventType`, `schemaVersion` and `eventId` into Kafka **headers**, so routing and tracing do not need to parse the body (`OutboxService.append`). A serialised example (synthetic ids, shape per the contract):

```json
{
  "eventId": "7d6f0c9e-0000-4000-8000-000000000001",
  "eventType": "InventoryReserved",
  "schemaVersion": 1,
  "occurredAt": "2026-10-18T10:15:30Z",
  "correlationId": "b1a2c3d4-0000-4000-8000-00000000a001",
  "causationId": "5c1d0000-0000-4000-8000-000000000009",
  "aggregateType": "Order",
  "aggregateId": "7f9c1e2a-5b3d-4c8e-9a10-0d2f4b6c8e01",
  "producer": "inventory-service",
  "payload": { "orderId": "7f9c1e2a-5b3d-4c8e-9a10-0d2f4b6c8e01", "reservationId": "...", "items": [], "expiresAt": "2026-10-18T10:30:30Z" }
}
```

`eventId` is the idempotency identity, `correlationId` ties the flow to the original HTTP request, and `causationId` points at the event or command that triggered this one, which makes saga debugging a matter of following a chain.

### Example 3: a consumer group and a listener

Listening is a few lines because the infrastructure is shared (`services/order-service/src/main/java/com/enterprise/oms/order/saga/OrderEventsListener.java`):

```java
@KafkaListener(topics = Topics.INVENTORY_EVENTS, containerFactory = "omsKafkaListenerContainerFactory")
void onInventoryEvent(ConsumerRecord<String, String> record) {
    routing.route(record);
}
```

The container factory `omsKafkaListenerContainerFactory` comes from platform-common (`OmsMessagingAutoConfiguration`), and the **group id is the service name** unless `oms.messaging.consumer-group` is set, so every replica of order-service shares one group and Kafka splits partitions among them. Parallelism is bounded by partitions: `oms.messaging.concurrency` (listener threads per container) defaults to 1 and the `prod` profile sets 3; replicas times concurrency beyond 3 means idle consumers (`docs/runbooks/02-kafka-consumer-lag.md`). Records are deserialised as plain strings and parsed by the router, not by Kafka deserialisers, so a malformed record becomes a controllable exception instead of a poison pill that kills the consumer.

## Intermediate tier

### Example 1: a reliable producer

All publishing goes through one `KafkaTemplate` (`OmsKafkaAutoConfiguration.omsKafkaTemplate`):

```java
cfg.put(ProducerConfig.ACKS_CONFIG, "all");
cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
cfg.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
cfg.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
cfg.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, props.getMaxBlockMs());
cfg.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, props.getRequestTimeoutMs());
cfg.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, props.getDeliveryTimeoutMs());
```

What each line means, as you should say it in an interview:

* `acks=all`: the leader waits for all in-sync replicas before acknowledging. Durability then depends on `min.insync.replicas`; with replication factor 1 (local) "all" means one copy.
* `enable.idempotence=true`: the broker de-duplicates producer retries using a producer id and per-partition sequence numbers, **within one producer session**. It keeps order with up to 5 in-flight requests. (Both settings are defaults in Kafka clients 3.x but are set explicitly so nobody changes them by accident.)
* `retries=Integer.MAX_VALUE` bounded by `delivery.timeout.ms`; here the template's defaults are `max.block.ms=3000`, `request.timeout.ms=5000`, `delivery.timeout.ms=10000` (`OmsKafkaProperties`), chosen so the relay stays responsive when the broker is down.
* The template is `KafkaTemplate<String,String>` with `observationEnabled`, so Micrometer tracing spans propagate.

### Example 2: the transactional outbox

The problem (ADR-0009): writing the database and then publishing to Kafka is a dual write; a crash between the two loses the event, and publishing before commit can publish an event for a rolled-back change. The fix is one extra table and a relay. The table, created by `V1__outbox_processed_audit.sql` in every service:

```sql
CREATE TABLE outbox_events (
    id             UUID          NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(64)   NOT NULL,
    aggregate_id   VARCHAR(64)   NOT NULL,
    event_type     VARCHAR(100)  NOT NULL,
    topic          VARCHAR(200)  NOT NULL,
    event_key      VARCHAR(200),
    payload        VARCHAR(20000) NOT NULL,
    headers        VARCHAR(4000),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at   TIMESTAMP WITH TIME ZONE,
    attempts       INTEGER       NOT NULL DEFAULT 0,
    last_error     VARCHAR(1000)
);
CREATE INDEX ix_outbox_pending ON outbox_events (published_at, created_at);
```

Appending is only legal inside the business transaction, which `Propagation.MANDATORY` enforces (`services/platform-common/src/main/java/com/enterprise/oms/common/outbox/OutboxService.java`):

```java
@Transactional(propagation = Propagation.MANDATORY)
public UUID append(EventEnvelope<?> envelope) {
    String topic = EventTypes.topicFor(envelope.eventType()).orElseThrow(() ->
            new IllegalArgumentException("Unknown eventType, pass the topic explicitly: " + envelope.eventType()));
    return append(topic, envelope.aggregateId(), envelope, Map.of());
}
```

The relay (`OutboxRelay`) polls every 500 ms (`oms.outbox.poll-interval-ms`), loads up to 50 pending rows oldest-first and publishes them in order, **stopping the batch on the first failure** so ordering is preserved and a broken broker is not hammered:

```java
for (OutboxEvent event : batch) {
    try {
        send(event);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
    } catch (Exception e) {
        log.warn("Outbox publish failed for event {} ({}): {}", event.getId(), event.getEventType(),
                e.toString());
        recordFailure(event, e);
        break;
    }
    markPublished(event);
    published++;
}
```

Guarantee: **at-least-once**. If the process dies after the broker acknowledged but before `published_at` is committed, the row is sent again. Published rows are deleted by a cleanup job after one hour (`oms.outbox.retention`, default `PT1H`). The saga code only ever calls `SagaMessaging`, which calls `outbox.append` inside the same transaction as the state change, history, saga log and audit rows, so "state changed" and "event will be published" are atomic. Alternatives you should be able to compare: CDC with Debezium (lower latency, no polling, needs Kafka Connect and logical replication, same table shape, listed as the upgrade path), Kafka transactions plus a DB transaction (no atomic commit across the two), and the "listen to yourself" pattern.

### Example 3: the idempotent consumer

Because delivery is at-least-once, every handler must be safe to run twice. `IdempotentEventProcessor` (`services/platform-common/src/main/java/com/enterprise/oms/common/idempotency/IdempotentEventProcessor.java`) makes the "have I seen this?" check and the business effect one transaction:

```java
@Transactional
public boolean processOnce(String consumer, String topic, EventEnvelope<?> envelope, Runnable effect) {
    ProcessedEvent.Key key = new ProcessedEvent.Key(consumer, envelope.eventId());
    if (repository.existsById(key)) {
        log.info("Duplicate event ignored: consumer={} eventId={} type={}", consumer, envelope.eventId(),
                envelope.eventType());
        metrics.duplicate(topic, envelope.eventType());
        return false;
    }
    repository.saveAndFlush(new ProcessedEvent(consumer, envelope.eventId(), envelope.eventType(), topic,
            Instant.now()));
    effect.run();
```

backed by `CONSTRAINT pk_processed_events PRIMARY KEY (consumer, event_id)`. The Javadoc documents the race: two concurrent deliveries both pass `existsById`, the loser fails on the primary key, its transaction rolls back so **its effect is not applied**, and the container retries it and then sees the duplicate. `EventRouter` composes the pieces: parse the envelope (malformed means non-retryable), restore the correlation id into the MDC, ignore unknown event types (counted in `oms.events.ignored`) and call `processOnce(consumer, topic, env, handler)`. Layer two is the domain: the order state machine treats an already-applied transition as a no-op and inventory keeps one reservation per `orderId`, so a re-sent *command* with a **new** `eventId` (the recovery job does this) is also harmless. Notification adds a third guard, unique (`source_event_id`, `template`).

## Advanced tier

### Example 1: error handling, retries and the dead-letter topic

The consumer pipeline (`KafkaConsumerConfigSupport`) is deliberately explicit. Offsets are committed by the container, not by auto-commit, and one record at a time:

```java
cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
cfg.putIfAbsent(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
...
factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
```

`AckMode.RECORD` commits the offset after the listener returns normally for that record, or after the error handler's recoverer has succeeded. Failures go through a `DefaultErrorHandler` with exponential back-off and a dead-letter recoverer:

```java
ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(Math.max(0, r.getMaxAttempts() - 1));
backOff.setInitialInterval(r.getInitialIntervalMs());
backOff.setMultiplier(r.getMultiplier());
backOff.setMaxInterval(r.getMaxIntervalMs());

DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(dltTemplate,
        (rec, ex) -> new TopicPartition(rec.topic() + ".DLT", -1));
```

Defaults (`MessagingProperties.Retry`): 4 delivery attempts, 500 ms initial, factor 2, max 5 s. That is three waits of 0.5, 1 and 2 seconds, about 3.5 seconds of back-off before dead-lettering. Some exceptions are never retried because retrying cannot help:

```java
public static final List<Class<? extends Exception>> NON_RETRYABLE = List.of(
        NonRetryableEventException.class,
        IllegalArgumentException.class,
        jakarta.validation.ConstraintViolationException.class,
        DeserializationException.class,
        MessageConversionException.class,
        ClassCastException.class);
```

The dead-lettered record keeps its key and gets headers `x-exception-class`, `x-exception-message`, `x-original-topic`, `x-original-partition` and `x-original-offset` (plus Spring's own `kafka_dlt-*`), and `oms.events.dead_lettered` is incremented. Behaviour verified by `ConsumerKafkaTest` on embedded Kafka: `poisonMessageIsRetriedWithBackoffThenDeadLettered`, `malformedJsonGoesStraightToDltWithoutRetriesAndDoesNotBlockThePartition`, `unknownEventTypeIsIgnoredAndCountedNotDeadLettered`. Trade-offs to articulate: this is **blocking** retry, so a failing record holds up its partition for the whole back-off (acceptable at 3.5 s; a long back-off would stall the partition and push lag up); the alternative is non-blocking retry topics (`@RetryableTopic`), which add topics and break per-key ordering. There is no automatic DLT replay tool in the service code; replays are an operator action after fixing the cause, safe because consumers deduplicate (`docs/runbooks/02-kafka-consumer-lag.md`). The related review scenario `docs/code-review/05-offset-ack-on-exception.md` (a teaching example of a flawed pull request) shows why swallowing exceptions and acknowledging in `finally` silently loses events.

### Example 2: delivery semantics and an honest answer on exactly-once

The vocabulary (`docs/architecture/delivery-semantics.md`): at-most-once loses messages, at-least-once duplicates them, exactly-once is "each effect once". Kafka's exactly-once is real but narrow: the idempotent producer removes duplicates caused by retries inside one session (producer id plus sequence), and transactions (`transactional.id`, `isolation.level=read_committed`, `sendOffsetsToTransaction`) make a consume-transform-produce loop **between Kafka topics** atomic. Neither can atomically include a PostgreSQL commit or an HTTP call. This platform therefore claims **effectively-once business effect**: at-least-once delivery from the outbox plus idempotent consumers. The failure windows, condensed:

| Window | Result | Signal |
|---|---|---|
| Crash before DB commit | Nothing happened; client retries with the same Idempotency-Key | none |
| Crash after commit, before relay publishes | Row waits, published later | `oms.outbox.pending` rises |
| Broker acked, relay crashes before `published_at` | Duplicate on the topic | `oms.events.duplicates` |
| Consumer crashes after DB commit, before offset commit | Redelivery hits the unique key and is skipped | `oms.events.duplicates` |
| Consumer crashes before DB commit | Rolled back; redelivery applies it first time | `oms.events.retries` |
| Poison message | Straight to DLT, partition keeps moving | `oms.events.dead_lettered` |

A subtle point from our own reading of the code (not stated in the docs): the relay waits `oms.outbox.send-timeout-ms` (default 5000) for the acknowledgement, while the template's `delivery.timeout.ms` is 10000. A broker that answers between 5 and 10 seconds causes the relay to give up and leave the row pending even though the record was ultimately written, so the next poll publishes it **again**. That is exactly the duplicate case the consumers are built for, but it illustrates why idempotent consumers are not optional.

Operational limits stated by the docs: `processed_events` and `idempotency_keys` must be retained at least as long as the maximum redelivery horizon (topic retention plus DLT replay window). Cleanup jobs exist for outbox rows and idempotency keys; **we found no purge job for `processed_events`** in the service code, so that table grows without bound until one is added (ADR-0005 lists it as a consequence).

### Example 3: ordering, rebalancing, lag and scaling

Ordering is per partition and therefore per key. Consequences the design handles on purpose: events for different orders are unordered relative to each other (not required); events of one order that travel on **different topics** are also unordered relative to each other (for example `OrderCancelled` on the order topic and `InventoryReserved` on the inventory topic). The code does not rely on cross-topic ordering: the order state machine has "already applied" no-ops and `InventoryReleased` handling for a late reservation, and inventory writes a `RELEASED` tombstone so a reservation that arrives after its release is refused. Ordering also holds only while the key and the partition count stay the same: raising partitions reshuffles keys, which is why the topic suffix and a dual-publish migration (`docs/architecture/event-schema-evolution.md`) matter. A hot key (one huge order storm) cannot be spread over partitions.

Rebalancing: when a member joins, leaves or misses `max.poll.interval.ms` (default 5 minutes) partitions move, and uncommitted work is redelivered to the new owner, which is another source of duplicates. A long GC pause or a slow handler can trigger it, which is why runbook 08 lists "consumers kicked from the group" as a JVM-memory symptom. The repository does not set `partition.assignment.strategy`, `session.timeout.ms` or static membership, so client defaults apply; saying "I would consider the cooperative sticky assignor and static group membership to reduce rebalance churn on rolling deploys" is a good senior answer.

Lag is exposed in two ways: Kafka's own tooling (`kafka-consumer-groups.sh --describe --group inventory-service`) and the application's `GET /api/v1/ops/messaging`, backed by `MessagingStatusService`, which uses a short-lived `Admin` client and never fails the endpoint (`lagAvailable: false` when the broker is unreachable):

```java
Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(consumerGroup())
        .partitionsToOffsetAndMetadata().get(timeout, TimeUnit.MILLISECONDS);
...
long end = latest.get(tp).offset();
long committedOffset = c == null ? 0L : c.offset();
lag += Math.max(0, end - committedOffset);
```

Scaling consumers on lag is demonstrated by `infrastructure/kubernetes/keda/scaledobject-kafka-lag.yaml` (a KEDA `ScaledObject` with `consumerGroup: inventory-service`, `topic: oms.inventory.commands.v1`, `lagThreshold: "50"`, `maxReplicaCount: 3` because the topic has 3 partitions). That file is an example, not part of any overlay.

### Example 4: schema evolution, retention and compaction

Evolution rules (`docs/architecture/event-schema-evolution.md`, ADR-0002): additive optional fields bump `schemaVersion` and need no topic change; removing, renaming, retyping or changing the meaning of a field is breaking and needs a new topic version with **dual publish** (two outbox rows in one transaction) until all consumer groups' lag on the old topic is zero and retention has passed. The mechanism that makes additive change safe is the tolerant reader (`EventJson.configure`):

```java
mapper.registerModule(new JavaTimeModule());
mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
// keep money scale (12.50 stays 12.50 when going through JsonNode)
mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
```

Consumers needing several `schemaVersion`s **upcast** old payloads at the edge, in the router handler. JSON Schemas are committed under `specs/events/*.schema.json` (15 payload schemas plus `envelope.schema.json`). The alternative is Avro or Protobuf with a schema registry: stronger compatibility enforcement and smaller records, but one more component, which was rejected for the Docker-less local stack; revisit if producers multiply.

Retention versus compaction: the business topics are **event logs** with time-based retention (7 days, DLT 30 days). Compaction (`cleanup.policy=compact`, keeps the latest record per key and uses null-value tombstones to delete) is the right choice for **state** topics such as "latest product snapshot per `productId`", where a new consumer should rebuild current state without replaying history. Compaction is not used here; it is a good answer to "when would you not use retention".

## How this repository implements it

| Concept | Where in repo | Notes |
|---|---|---|
| Topic names, partitions, DLT | `services/platform-common/src/main/java/com/enterprise/oms/common/events/Topics.java`, `infrastructure/compose/kafka/create-topics.sh`, `infrastructure/kubernetes/dev-dependencies/files/create-topics.sh` | 3 partitions, 7 d / 30 d retention |
| Envelope, tolerant reader | `services/platform-common/src/main/java/com/enterprise/oms/common/events/EventEnvelope.java`, `EventJson.java`, `specs/events/*.schema.json` | JSON, no registry |
| Reliable producer | `services/platform-common/src/main/java/com/enterprise/oms/common/autoconfigure/OmsKafkaAutoConfiguration.java`, `OmsKafkaProperties.java` | acks=all, idempotent |
| Transactional outbox | `services/platform-common/src/main/java/com/enterprise/oms/common/outbox/OutboxService.java`, `OutboxRelay.java`, `services/platform-common/src/main/resources/db/common/V1__outbox_processed_audit.sql` | Polling relay, at-least-once |
| Idempotent consumer | `services/platform-common/src/main/java/com/enterprise/oms/common/idempotency/IdempotentEventProcessor.java`, `services/platform-common/src/main/java/com/enterprise/oms/common/messaging/EventRouter.java` | `processed_events` PK |
| Container, retry, DLT | `services/platform-common/src/main/java/com/enterprise/oms/common/messaging/KafkaConsumerConfigSupport.java`, `MessagingProperties.java` | 4 attempts, 0.5 s doubling to 5 s |
| Listeners | `services/order-service/src/main/java/com/enterprise/oms/order/saga/OrderEventsListener.java`, `services/inventory-service/src/main/java/com/enterprise/oms/inventory/messaging/InventoryEventsListener.java`, `services/notification-service/src/main/java/com/enterprise/oms/notification/messaging/NotificationEventsListener.java`, `services/fulfillment-service/src/main/java/com/enterprise/oms/fulfillment/messaging/StartFulfillmentListener.java` | One router per service |
| Lag and ops | `services/platform-common/src/main/java/com/enterprise/oms/common/messaging/MessagingStatusService.java`, `OpsMessagingController.java`, `docs/runbooks/02-kafka-consumer-lag.md` | `lagAvailable` degradation |
| Autoscaling on lag | `infrastructure/kubernetes/keda/scaledobject-kafka-lag.yaml` | Example only |
| Embedded broker | `services/local-stack/src/main/java/com/enterprise/oms/localstack/LocalKafkaBroker.java` | Single node, RF 1, port 19092 |
| Tests | `ConsumerKafkaTest`, `OutboxRelayKafkaTest`, `OutboxRelayResilienceTest` in `platform-common`; `OrderSagaKafkaIT`, `OrderResilienceKafkaIT` in order-service | Embedded KRaft, no Docker |

### Demonstration-only vs production-grade

* **Production-grade**: transactional outbox and idempotent consumers with atomic markers, explicit non-retryable classification, DLT with diagnostic headers, ops endpoint for lag, tolerant reader and documented evolution rules, `acks=all` plus idempotent producer, manual offset control, KEDA example capped at partition count.
* **Demonstration-only / local limits**: replication factor 1 locally, embedded single-broker KRaft, and `create-topics.sh` hard-codes `min.insync.replicas=1` (production on MSK would use replication factor 3 with `min.insync.replicas=2`; the script takes `PARTITIONS` and `REPLICATION` from the environment but not the ISR setting); JSON without a schema registry, so schema checks rely on contract tests; the polling relay adds latency and runs on every replica with no `FOR UPDATE SKIP LOCKED` row claiming (duplicates are tolerated, throughput is not optimised); no processed-events purge job found; no automated DLT replay tooling; no `@RetryableTopic` non-blocking retries; client rebalance settings left at defaults.
* **Verification honesty**: the Kafka behaviour is covered by embedded KRaft tests; Testcontainers Kafka and PostgreSQL tests are annotated `@Testcontainers(disabledWithoutDocker = true)` and are skipped on the dev machine because Docker is not installed; real-broker failure injection (killing a broker or the relay mid-publish) has not been exercised, as `delivery-semantics.md` states. The runbook commands are illustrative and were not run against a live cluster.

## Common mistakes and gotchas

* Dual write: `kafkaTemplate.send` inside `@Transactional` business code (phantom or lost events). Use the outbox. See `docs/code-review/04-kafka-publish-in-tx.md` for the teaching scenario.
* Catching all exceptions in a listener and acknowledging anyway, so failures never retry and never reach the DLT.
* Claiming "exactly-once" because `enable.idempotence=true`. It covers producer retries within a session only.
* Putting the idempotency marker in a different transaction from the business effect, or using an in-memory set.
* Deduplicating by a field the producer regenerates on retry (a new `eventId` for a re-sent command): that is why state-machine and unique-reservation idempotency exist as the second layer.
* Choosing a key with poor cardinality (hot partition) or changing the key or partition count after launch (ordering breaks).
* Assuming ordering across topics or across keys.
* More consumers than partitions; forgetting `concurrency` times replicas.
* Long handlers or GC pauses exceeding `max.poll.interval.ms`, causing rebalance loops.
* Retrying poison messages forever instead of classifying non-retryable errors.
* Logging whole event payloads (emails) or labelling metrics with ids.
* Purging `processed_events` or idempotency keys sooner than the redelivery horizon.
* Resetting consumer offsets in production without a written reason: it skips or repeats saga steps.
* Treating `acks=all` as durable on replication factor 1.

## Quick-recall cheat sheet

* Topic = log split into partitions; order only within a partition; key decides the partition; same key means same partition means ordered.
* Consumer group: each partition has one consumer in the group; max useful consumers = partitions; group id here = service name.
* Offset commit: auto-commit off, `AckMode.RECORD`; commit after success or after the DLT recoverer.
* Producer: `acks=all`, idempotence on, `max.in.flight<=5`, bounded timeouts. Idempotent producer dedupes retries within a session only.
* Semantics: at-most-once, at-least-once (outbox plus retries), exactly-once only inside Kafka (transactions); here effectively-once business effect.
* Outbox: row in the same DB transaction as the state change; relay publishes in order, marks `published_at`; crash window means duplicates; cleanup after 1 h.
* Idempotent consumer: `processed_events (consumer, event_id)` primary key in the same transaction; domain-level idempotency as backup.
* Errors: 4 attempts, 500 ms doubling to 5 s, then `<topic>.DLT` with `x-exception-*` and `x-original-*`; non-retryable list skips retries.
* Lag = log-end offset minus committed offset per partition; `GET /api/v1/ops/messaging`; KEDA scales on lag up to the partition count.
* Schema evolution: additive plus `schemaVersion` plus tolerant reader; breaking means new topic version with dual publish.
* Retention: 7 d business, 30 d DLT; compaction is for latest-state-per-key topics (not used here).
* Rebalance causes: join, leave, `max.poll.interval.ms` exceeded; handlers must be idempotent anyway.
* Local limits to admit: RF 1, single embedded broker, no registry, relay without row claiming, Docker-based tests skipped.

# Delivery semantics

Decision records: [ADR-0009 outbox](../adr/0009-transactional-outbox.md), [ADR-0005 idempotency](../adr/0005-idempotency-strategy.md), [ADR-0002 events](../adr/0002-kafka-event-design.md). Contract: [section 7](00-shared-contract.md).

## 1. The three vocabularies

* At-most-once: send, never retry. Messages can be lost; no duplicates. Not acceptable for orders.
* At-least-once: retry until acknowledged. No loss (given durable storage), duplicates possible. This is what the platform provides on the wire.
* Exactly-once: each message's effect happens once. Kafka provides this only inside Kafka (idempotent producer within a session, transactions for read-process-write between topics). It cannot atomically include a PostgreSQL commit or an HTTP call to a third party. This platform does NOT claim exactly-once across database plus Kafka plus external systems.
* Effectively-once (what we claim): at-least-once delivery plus idempotent consumers, so the business effect is applied once even when the message is delivered more than once.

## 2. The chain

1. State change and event are written in one DB transaction: row in `outbox_events` (`OutboxService`, `services/platform-common/.../common/outbox`).
2. `OutboxRelay` publishes with `acks=all`, `enable.idempotence=true`, key = `orderId`, then marks `published_at`.
3. Consumers (`omsKafkaListenerContainerFactory`, `AckMode.RECORD`) process one record: in one DB transaction, insert `processed_events(consumer, event_id)` (unique) and apply the business effect via `IdempotentEventProcessor`, then the offset commits.
4. Failures: `DefaultErrorHandler` retries 4 times (500 ms doubling to 5 s) for transient errors; deserialization, validation and `IllegalArgumentException` are non-retryable; exhausted records are published to `<topic>.DLT` with `x-exception-*` and `x-original-*` headers by `DeadLetterPublishingRecoverer`.
5. The state machine and unique reservation per order make a repeated logical command harmless even when its `eventId` differs.

## 3. Failure windows

| Window | What happens | Result | Signal |
|---|---|---|---|
| Crash before DB commit | Neither state nor outbox row exists | Nothing happened; client retries with the same Idempotency-Key | none needed |
| Crash after commit, before relay publishes | Outbox row waits | Published after restart; delayed, not lost | `oms.outbox.pending` rises |
| Broker down or `acks=all` not satisfied | Relay retries; row stays unpublished | Delay | outbox pending, relay errors |
| Broker acked, relay crashes before marking `published_at` | Row re-published | Duplicate on topic | consumers log duplicate, `oms.events.duplicates` |
| Producer retry inside a session | Idempotent producer dedupes by sequence | No duplicate from this cause | none |
| Consumer crashes after DB commit, before offset commit | Redelivery | Second delivery hits unique `processed_events` and is skipped | `oms.events.duplicates` |
| Consumer crashes before DB commit | Transaction rolled back | Redelivery applies it for the first time | `oms.events.retries` |
| Poison message | Non-retryable classification, straight to DLT | Others on the partition continue | `oms.events.dead_lettered`, DLT topic |
| Consumer processes but its own reply is lost | Saga step stuck | `SagaRecoveryJob` re-sends the idempotent command after `oms.saga.stuck-after-seconds` | `oms.saga.active`, step log attempts |
| Rebalance during processing | Possible duplicate processing by another instance | Unique constraint serializes: one wins, one skipped | duplicates |
| External call (simulated notification) succeeds but DB write fails | External effect may repeat on retry | Real providers need their own idempotency key; here simulated | `notification_attempts` |
| Partition order across orders | Not guaranteed | Not required; per-order ordering holds via key | |

## 4. Honest limits

* Duplicates reach the topic by design; protection is entirely on the consumer side.
* `processed_events` and `idempotency_keys` must be retained at least as long as the maximum redelivery horizon (topic retention plus DLT replay window); purging earlier reopens duplicate risk.
* DLT replay re-injects old events; handlers must tolerate being applied late (the state machine rejects or ignores stale transitions).
* Ordering holds per order only while the key and partition count are unchanged.
* Business-level inconsistency from lost compensation is handled by recovery and reconciliation ([saga-and-consistency.md](saga-and-consistency.md)), not by delivery guarantees.
* Verification status: unit and embedded-Kafka tests cover the logic locally. Tests that need Docker (Testcontainers PostgreSQL/Kafka) are skipped on the dev machine, and Compose, Kubernetes and monitoring manifests are statically validated only, so real-broker failure injection (killing a broker or relay mid-publish) has not been exercised there.

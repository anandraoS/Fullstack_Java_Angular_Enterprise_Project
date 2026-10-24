# Mentoring: Debugging

All commands are illustrative: not executed against a live cluster. Ports are the local no-Docker ports from the contract (gateway 18080, order 18081, inventory 18082, fulfillment 18083, notification 18084, Kafka 19092). `<JWT>` means a token obtained from the dev token endpoint; never paste real tokens into tickets.

---

## 1. "The order is stuck" - start with the correlationId

**Junior:** A customer says order `7f9c...` has been PROCESSING for ten minutes. I grepped for the order id and found three log lines.

**Reviewer:** The order id appears only where a developer remembered to log it. The `correlationId` is injected by `CorrelationIdFilter` (verified, platform-common/web), put in MDC, echoed in `X-Correlation-Id`, copied into every event envelope and Kafka header, and stored on the order JSON. Start there.

```bash
# 1. get the order and its correlationId (illustrative: not executed against a live cluster)
curl -s -H "Authorization: Bearer <JWT>" http://localhost:18080/api/v1/orders/<orderId> | jq '{status, statusReason, correlationId, updatedAt}'

# 2. saga view (OPERATOR/ADMIN)
curl -s -H "Authorization: Bearer <JWT>" http://localhost:18080/api/v1/admin/saga/<orderId> | jq '{state, currentStep, attempts, lastError, steps}'

# 3. grep every service log for the same id (local run writes logs under .run/ when scripts/run-local.sh is used)
grep -h '<correlationId>' .run/*.log | sort
```

Reading the output: the saga `currentStep` and the last `steps[]` entry tell you which hop did not answer. `RESERVE_INVENTORY` with `attempts` rising and nothing from inventory in the logs means the command did not arrive or was not consumed; go to consumer lag. `lastError` populated means the orchestrator has seen a failure.

**Takeaway:** correlationId first, saga log second, then the service that owns the next step.

---

## 2. Reading a dead-letter record

**Junior:** There are records in `oms.inventory.events.v1.DLT`. What now?

```bash
# illustrative: not executed against a live cluster
kafka-console-consumer.sh --bootstrap-server localhost:19092 \
  --topic oms.inventory.events.v1.DLT --from-beginning --max-messages 5 \
  --property print.key=true --property print.headers=true --property print.timestamp=true
```

Per contract section 12, DLT records carry `x-exception-class`, `x-exception-message`, `x-original-topic`, `x-original-partition`, `x-original-offset` besides Spring's `kafka_dlt-*` headers, and keep the key (the orderId).

Decision tree:

| `x-exception-class` | Meaning | Action |
|---|---|---|
| `MalformedEventException` / deserialization | producer sent bad JSON or schema drift | fix producer; compare with `specs/events/*.schema.json`; the record is not replayable as-is |
| `NonRetryableEventException` / `IllegalArgumentException` | validation failed on a field | find the bug; record is poison |
| `InvalidStateTransitionException` | unexpected ordering (should have been no-op if duplicate) | check state machine redelivery handling |
| `DataAccessException`, timeouts | transient, retries exhausted | fix the dependency, then replay |

Replaying is safe only because consumers are idempotent (`processed_events`). Also check `GET /api/v1/ops/messaging` `deadLettered` and `dltTopics`.

---

## 3. Duplicate or missing effects: prove it with processed_events

```sql
-- illustrative: not executed against a live cluster (PostgreSQL, order_db)
SELECT event_id, consumer, event_type, topic, processed_at
  FROM processed_events
 WHERE consumer = 'order-service'
 ORDER BY processed_at DESC LIMIT 20;
```

(Columns match `services/platform-common/src/main/resources/db/common/V1__outbox_processed_audit.sql`; primary key is `(consumer, event_id)`.)

If an event id is present, the effect was applied; a second delivery shows up as `oms.events.duplicates`, not as a second effect. If an effect is missing but the id is absent, the consumer never committed: check logs for retries and the DLT.

---

## 4. Thread dump and pool starvation

Symptom: high latency, CPU low, `hikaricp_connections_pending > 0`.

```bash
# illustrative: not executed against a live cluster
jcmd <pid> Thread.print > threads.txt
grep -c 'HikariPool.*getConnection' threads.txt          # threads waiting for a DB connection
grep -B2 -A12 'com.enterprise.oms' threads.txt | head -80  # what our code is doing
```

Many threads in `HikariPool.getConnection` while others sit in `SocketInputStream.read` / `RestClient` mean connections are held during a remote call (see `performance.md` section 2). Take two dumps 10 seconds apart; a thread in the same frame in both is stuck, not busy.

---

## 5. Using logs well

* Level conventions: INFO for state transitions (`order=... CREATED->VALIDATED`), WARN for retries and ignored events, ERROR only when action is needed.
* Always log ids, never payloads. Emails are masked via `LogMasking.maskEmail` (verified).
* Locally the console format is plain; in containers the `json-logs` profile emits `timestamp, level, service, correlationId, traceId, spanId, logger, message`. Filter JSON logs with `jq 'select(.correlationId=="...")'`.

## 6. Reproduce before fixing

1. Write a failing test at the lowest layer that shows it (unit for state machine, MockMvc for HTTP, EmbeddedKafka for consumers).
2. Fix.
3. Keep the test. Reference it in the runbook's "regression test" section.

For a live reproduction without Docker use `scripts/run-local.sh` (exists; starts embedded Kafka plus 4 services and the gateway as JVMs). It was run on 2026-10-18 and the live flows in `docs/validation/validation-report.md` ran against it (about 2.5 minutes to start; it refuses to start if a needed port is taken). The debugging exercises themselves were not all executed.

## Checklist

- [ ] correlationId collected before anything else.
- [ ] Saga log, order history and audit read before touching data.
- [ ] DLT headers read; poison vs transient decided.
- [ ] Failing test first; same test kept.
- [ ] Nothing sensitive pasted into tickets or chat.

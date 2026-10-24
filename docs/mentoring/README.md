# Mentoring Guides

Six short guides written as code-review conversations between a Reviewer (senior) and a Junior engineer. Every dialogue is drawn from a real design decision in this platform (see `docs/architecture/00-shared-contract.md`, the normative design).

Status note (refreshed 2026-10-18): all modules now exist on disk. Snippets marked `sketch` are **teaching reconstructions that follow the contract**, not copies of committed files; each guide lists the classes verified on disk. The actual implementation may differ in naming; the reasoning does not.

| Guide | Theme | Main codebase hooks |
|---|---|---|
| [solid.md](solid.md) | SRP, OCP, LSP, ISP, DIP applied to the order and inventory services | `OrderStateMachine` (exists, `order/domain/`), `IdempotentEventProcessor`, `EventRouter` |
| [design-patterns.md](design-patterns.md) | Strategy (reservation), State machine, Outbox, Saga, Factory, Adapter | `OutboxService`, `OutboxRelay`, `OmsRestClientFactory`, `EventRouterFactory` |
| [testing.md](testing.md) | Test pyramid, concurrency tests, Kafka tests without Docker, honest skipping | `platform-test-support`, `ConsumerKafkaTest`, `OutboxRelayKafkaTest` |
| [security.md](security.md) | BOLA, mass assignment, JWT claims, log hygiene, 404-not-403 | `CurrentUser`, `LogMasking`, `ProblemSecurityHandlers` |
| [performance.md](performance.md) | Conditional UPDATE vs locks, pagination, N+1, pool sizing, outbox batching | `PageRequestFactory`, `OutboxProperties` |
| [debugging.md](debugging.md) | Following a correlationId, reading DLT headers, saga step log, thread dumps | `CorrelationIdFilter`, `OpsMessagingController`, `OpsAuditController` |

Related material:

* Concrete PR-level scenarios with fixes and regression tests: [../code-review/README.md](../code-review/README.md)
* Operational exercises: [../runbooks/README.md](../runbooks/README.md)
* AI-generated flawed code to practise reviewing: [../../ai-engineering/flawed-ai-examples/](../../ai-engineering/flawed-ai-examples/)

## How to use these guides

1. Read the dialogue first and try to spot the problem in the "Before" snippet before the Reviewer does.
2. Compare your fix with "Better version".
3. Use the takeaway checklist at the end of each guide as a PR review checklist.

## Verified-on-disk reference (checked when this doc was written)

Under `services/platform-common/src/main/java/com/enterprise/oms/common/`: `outbox/{OutboxService,OutboxRelay,OutboxEvent}`, `idempotency/IdempotentEventProcessor`, `messaging/{EventRouter,EventRouterFactory,MessagingMetrics,OpsMessagingController,KafkaConsumerConfigSupport,NonRetryableEventException}`, `security/{CurrentUser,JwtTokenFactory,ProblemSecurityHandlers,ServiceTokenProvider}`, `observability/LogMasking`, `web/{CorrelationIdFilter,PageRequestFactory,IdempotencyKeys,RequestSizeLimitFilter}`, `error/{GlobalExceptionHandler,ProblemFactory,ErrorCode}`, `http/OmsRestClientFactory`.

Also verified later on disk: `services/order-service/.../order/domain/{OrderStateMachine,OrderStatus,OrderTrigger}`, `services/inventory-service/.../inventory/reservation/{ReservationStrategy,AtomicUpdateReservationStrategy,PessimisticLockReservationStrategy,OptimisticVersionReservationStrategy}`, `services/inventory-service/.../service/ReservationService`, `gateway/` (pom and sources).

Also verified on disk (2026-10-18): `OrderSagaOrchestrator`, `SagaRecoveryJob`, `ReconciliationService` (`services/order-service/.../order/saga/`), `services/notification-service` (`NotificationService`, `NotificationDeliveryJob`), `RetentionPurgeJob` (platform-common). Nothing is pending; remaining gaps are in `docs/known-limitations.md`. See `docs/_todo-link-check.md` for the resolution table.

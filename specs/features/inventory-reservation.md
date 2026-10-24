# Feature spec: Inventory reservation

Status: Implemented in inventory-service (domain, strategies, messaging listener, REST API, expiry job) and integration-tested on H2 plus embedded Kafka (2026-10-18). PostgreSQL ITs skipped without Docker.
Owner: inventory-service     Date: 2026-10
Related: contract sections 7 (reservation lifecycle) and 9 (seed data); ADR-0006 (concurrency), ADR-0005 (idempotency);
runbook `docs/runbooks/03-inventory-reservation-failures-under-concurrency.md`;
flawed example `../../flawed-ai-examples/01-check-then-act-oversell.md`.

## 1. Problem and goal
When an order is validated, hold stock for it so concurrent orders cannot sell the same units, release the hold on
cancel/failure/expiry, and make the hold permanent only when the order is confirmed. Success = `reserved <= on_hand` always,
exactly the available quantity is reservable under contention, and a duplicate command never reserves twice.

## 2. Scope
In: `ReserveInventory`, `ReleaseInventory` handling, TTL expiry, commit on `OrderConfirmed`, ship on `FulfillmentCompleted`, events
`InventoryReserved/ReservationFailed/Released`, strategy comparison (atomic / pessimistic / optimistic).
Out: backorders, warehouse allocation, multi-location stock, partial reservations (all-or-nothing only).

## 3. Actors
SERVICE messaging only (commands from order-service). OPERATOR/ADMIN read reservations (`GET /api/v1/inventory/reservations`)
and adjust stock (`PUT /api/v1/inventory/{id}/stock`, never below `reserved`).

## 4. Functional requirements
* FR-1 `ReserveInventory{orderId, items, reservationTtlSeconds}` reserves every line or none (one transaction).
* FR-2 Insufficient stock for any line -> no change, emit `InventoryReservationFailed{reason, shortages[]}`.
* FR-3 Unknown or inactive product -> refused without touching stock.
* FR-4 Duplicate lines for the same product are merged; requests with invalid quantities are rejected as illegal arguments (non-retryable).
* FR-5 Duplicate `ReserveInventory` for the same orderId returns the ORIGINAL outcome (unique `order_id`), including a previously FAILED outcome, even if stock appears later.
* FR-6 TTL defaults to `oms.inventory.reservation-ttl-seconds` (900) and is capped.
* FR-7 Rows are updated in sorted productId order so opposite-order requests cannot deadlock.
* FR-8 Lifecycle: `RESERVED -> COMMITTED -> (shipped)`, `RESERVED|COMMITTED -> RELEASED`, `RESERVED -> EXPIRED` by scheduler (emits `InventoryReleased{reason:EXPIRED}`).
* FR-9 Default strategy is the atomic conditional UPDATE; pessimistic (`SELECT ... FOR UPDATE`) and optimistic (`@Version` + retry) are selectable for comparison.

## 5. Acceptance criteria
* AC-1 Given SKU stock 10, When 50 threads each reserve 1 concurrently, Then exactly 10 succeed and `reserved == 10` (every strategy).
* AC-2 Given multi-unit orders racing for limited stock, Then no product's `reserved` exceeds `on_hand` or goes negative.
* AC-3 Given two orders with the same products in opposite item order, When they run concurrently, Then no deadlock and the ledger is consistent.
* AC-4 Given the same `ReserveInventory` delivered concurrently N times, Then one reservation exists.
* AC-5 Given enough stock, When reserved, Then `InventoryReserved` is emitted and `reserved` increases by the quantities.
* AC-6 Given a shortage on one line of several, Then nothing is reserved on any line and the shortage lists requested vs available.
* AC-7 Given a duplicate command, Then the original outcome is returned and stock is reserved once; a duplicate of a FAILED reservation stays FAILED.
* AC-8 Given an unknown or inactive product, Then refused with no stock change.
* AC-9 Given TTL not supplied or oversized, Then default applied or value capped.
* AC-10 Given a reservation past `expires_at` still `RESERVED`, When the scheduler runs, Then `EXPIRED`, `reserved -= q`, `InventoryReleased{EXPIRED}` emitted. (covered, section 9)
* AC-11 Given `ReleaseInventory` handled twice, Then stock released once. (covered, section 9)
* AC-12 Given a stock adjustment that would drop `on_hand` below `reserved`, Then 409 `CONFLICT`. (covered, section 9)
* AC-13 Seed: SKU-1019 stock 3 and SKU-1020 stock 0 produce insufficient-stock outcomes for demo orders. (covered, section 9)

## 6. Design (as implemented; see packages)
`com.enterprise.oms.inventory.service.ReservationService#reserve(orderId, items, ttlSeconds, correlationId, ...)` returns
`ReservationOutcome`; strategies in `com.enterprise.oms.inventory.reservation`: `AtomicUpdateReservationStrategy`,
`PessimisticLockReservationStrategy`, `OptimisticVersionReservationStrategy`, selected via `ReservationStrategies`; shortage
reporting by `ShortageCalculator`; contention signalled by `ReservationContentionException`. Domain: `Stock`, `Reservation`,
`ReservationItem`, `ReservationStatus`, `StockMovement`. Repositories in `...inventory.repo`. Migration:
`services/inventory-service/src/main/resources/db/migration/V10__inventory_schema.sql`.
Atomic statement (contract section 7): `UPDATE stock SET reserved = reserved + :q, version = version + 1 WHERE product_id = :id AND on_hand - reserved >= :q`;
0 affected rows means insufficient.

## 7. Non-functional
Metrics via `InventoryMetrics` (names per contract); reservation latency and contention counts should be measured with `performance/` k6 (never run) and the strategy
benchmark (`StrategyBenchmarkTest`, opt-in, skipped by default) before choosing a different default.

## 8. Security considerations
Commands accepted only through the messaging path; payload treated as data (validated quantities, product ids); forged-command risk
mitigated by broker ACLs (production) and consumer validation; stock adjustment is OPERATOR/ADMIN with audit and stock_movement.

## 9. Traceability (verified by grep, 2026-10)
Parameterised by strategy name (`ReservationStrategies`): each test runs for every strategy.
| AC | Test | Status | Docker |
|---|---|---|---|
| AC-1 | `ReservationConcurrencyTest#fiftyThreadsCompeteForTenUnitsAndExactlyTenAreReserved` | exists | no |
| AC-2 | `ReservationConcurrencyTest#multiUnitOrdersNeverOversellAndNeverGoNegative`, `#contendedMultiProductOrdersKeepTheLedgerConsistent` | exists | no |
| AC-3 | `ReservationConcurrencyTest#oppositeItemOrdersDoNotDeadlock` | exists | no |
| AC-4 | `ReservationConcurrencyTest#concurrentDuplicateReserveCommandsCreateOneReservation` | exists | no |
| AC-5 | `ReservationServiceTest#reservesAvailableStockAndEmitsInventoryReserved` | exists | no |
| AC-6 | `ReservationServiceTest#refusesWhenStockIsInsufficientAndReportsTheShortage`, `#isAllOrNothingAcrossLines` | exists | no |
| AC-7 | `ReservationServiceTest#duplicateOrderReturnsTheOriginalOutcomeWithoutReservingTwice`, `#duplicateOfAFailedReservationStaysFailedEvenIfStockAppearsLater` | exists | no |
| AC-8 | `ReservationServiceTest#unknownProductIsRefusedWithoutTouchingStock`, `#inactiveProductIsRefused` | exists | no |
| FR-4 | `ReservationServiceTest#duplicateLinesOfTheSameProductAreMerged`, `#invalidRequestsAreRejectedAsIllegalArguments` | exists | no |
| AC-9 | `ReservationServiceTest#ttlDefaultsToConfiguredValueAndIsCapped` | exists | no |
| AC-10 | `ReservationLifecycleTest#expiryReleasesOverdueReservationsAndEmitsInventoryReleasedWithReasonExpired`, `#expiryLeavesCommittedAndNotYetDueReservationsAlone`, `#commitRacingTheExpiryJobEndsInExactlyOneConsistentState` (job: `ReservationExpiryJob`) | exists | no |
| AC-11 | `ReservationLifecycleTest#releaseIsIdempotentAndTheCommandIsAnsweredAgainButTheEventStaysSilent`, `#releaseGivesStockBackEmitsInventoryReleasedAndWritesTheLedger` | exists | no |
| AC-12 | `InventoryApiTest#adjustingBelowTheReservedQuantityIsRejectedWith409AndChangesNothing`, `#adjustValidationAndAuthorization`, `#adjustingWhileReservationsRunNeverBreaksTheReservedInvariant` | exists | no |
| AC-13 | `SeedDataTest` (inventory seed `V1000__seed_products.sql`), `scripts/live-flows.mjs` insufficient-stock flows (SKU-1020 stock 0, SKU-1019 over-ask), `tests/e2e/oversell-live.mjs` (30 orders, stock 3: 3 FULFILLED, 27 REJECTED) | exists (live run 2026-10-18) | no |
PostgreSQL variants (`InventoryPostgresIT`, `InventoryLocalPostgresIT`) exist but are skipped without Docker; the pessimistic strategy was never run on real PostgreSQL (`docs/known-limitations.md`).
All "exists" rows were located by grep; the reactor run of 2026-10-18 (`docs/validation/backend-test-summary.md`) executed them, all green on H2.

## 10. Rollout and open questions
Additive; strategy chosen by configuration (check `InventoryProperties`). Open: default TTL under slow fulfillment; per-product reservation caps.

## 11. Verification record
Test method names verified by grep on 2026-10-18; execution results are in `docs/validation/backend-test-summary.md`. k6 (`performance/oversell.js`) has never been run (no k6 binary).

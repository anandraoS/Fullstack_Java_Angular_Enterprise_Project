# TDD narrative: the order state machine

This is the real history of how `OrderStateMachine` (`services/order-service/src/main/java/com/enterprise/oms/order/domain/OrderStateMachine.java`)
was built, test first. The code excerpts and the Maven output below are copied from the runs made while building it,
not reconstructed. The contract it implements is section 6 of `docs/architecture/00-shared-contract.md`.

Why this class is worth TDD: it is pure (no Spring, no I/O, no clock), it is the heart of the saga, and a wrong
transition is a business bug (an order cancelled after it shipped). A table of 9 statuses x 12 triggers is also exactly
the kind of thing that looks right on review and is wrong in one cell.

Command used for every cycle (inline JAVA_HOME, nothing exported):

```
JAVA_HOME=/c/Users/sahukari.rao/.jdks/graalvm-jdk-17.0.12 .../mvn -B -f services/pom.xml -pl order-service test -Dtest=OrderStateMachineTest
```

## Cycle 1: the smallest behaviour

**Test first.** Before the class existed, only the two enums `OrderStatus` / `OrderTrigger` (vocabulary from the contract)
and this test:

```java
@Test
void createdMovesToValidatedWhenValidationPasses() {
    OrderStateMachine.Decision d = OrderStateMachine.decide(OrderStatus.CREATED, OrderTrigger.VALIDATION_PASSED);

    assertThat(d.kind()).isEqualTo(OrderStateMachine.Kind.TRANSITION);
    assertThat(d.to()).isEqualTo(OrderStatus.VALIDATED);
}
```

**Red (does not compile):**

```
[ERROR] COMPILATION ERROR :
[ERROR] .../OrderStateMachineTest.java:[12,26] package OrderStateMachine does not exist
[ERROR] .../OrderStateMachineTest.java:[12,40] cannot find symbol
[ERROR] .../OrderStateMachineTest.java:[14,57] package OrderStateMachine does not exist
[INFO] BUILD FAILURE
```

Writing the test already forced two design decisions: a `Decision` carries a `kind` AND a target status, and the API is
a plain function `decide(status, trigger)` (no entity, no Spring: testable in microseconds).

**Green, minimal code:** hard-code exactly one rule.

```java
public static Decision decide(OrderStatus from, OrderTrigger trigger) {
    if (from == OrderStatus.CREATED && trigger == OrderTrigger.VALIDATION_PASSED) {
        return new Decision(Kind.TRANSITION, OrderStatus.VALIDATED);
    }
    throw new UnsupportedOperationException("not yet");
}
```

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## Cycle 2: more behaviour, still naive

**Tests first** (added to the same class): cancelling after a reservation needs compensation, cancelling before one is
immediate, a fulfilled order cannot be cancelled, and a replayed validation result is harmless.

```java
@Test
void cancelAfterReservationNeedsCompensation() {
    var d = OrderStateMachine.decide(OrderStatus.INVENTORY_RESERVED, OrderTrigger.CANCEL_REQUESTED);
    assertThat(d.kind()).isEqualTo(OrderStateMachine.Kind.TRANSITION);
    assertThat(d.to()).isEqualTo(OrderStatus.COMPENSATION_PENDING);
}

@Test
void fulfilledOrderCannotBeCancelled() {
    assertThat(OrderStateMachine.decide(OrderStatus.FULFILLED, OrderTrigger.CANCEL_REQUESTED).kind())
            .isEqualTo(OrderStateMachine.Kind.INVALID);
}

@Test
void replayedValidationOutcomeIsANoOp() {
    assertThat(OrderStateMachine.decide(OrderStatus.VALIDATED, OrderTrigger.VALIDATION_PASSED).kind())
            .isEqualTo(OrderStateMachine.Kind.NO_OP);
}
```

The third test is where the third `Kind` came from: the contract says "applying an already-applied event again is a
no-op", and a Kafka consumer sees duplicates and late replays all day. Distinguishing `NO_OP` (harmless, already done)
from `INVALID` (a real conflict, 409 for a user command) is the main modelling decision of the whole class.

**Red (assertions, not compilation):**

```
[ERROR] Tests run: 5, Failures: 0, Errors: 4, Skipped: 0 <<< FAILURE! -- in ...OrderStateMachineTest
java.lang.UnsupportedOperationException: not yet     (x4)
[INFO] BUILD FAILURE
```

**Green, deliberately naive:** a `switch` per trigger covering only what the tests demand.

```java
switch (trigger) {
    case VALIDATION_PASSED:
        if (from == CREATED)   return new Decision(Kind.TRANSITION, VALIDATED);
        if (from == VALIDATED) return new Decision(Kind.NO_OP, VALIDATED);
        return new Decision(Kind.INVALID, null);
    case CANCEL_REQUESTED:
        if (from == CREATED || from == VALIDATED)              return new Decision(Kind.TRANSITION, CANCELLED);
        if (from == INVENTORY_RESERVED || from == CONFIRMED)   return new Decision(Kind.TRANSITION, COMPENSATION_PENDING);
        return new Decision(Kind.INVALID, null);
    default:
        throw new UnsupportedOperationException("not yet");
}
```

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## Cycle 3: exhaustive tests, then the refactor to tables

Ten triggers were still `UnsupportedOperationException`. Rather than add one test per trigger, the contract table was
transcribed into the test as DATA, written independently of the implementation, and every combination is asserted:

```java
/** Every valid (from, trigger) -> to of contract section 6. */
private static final List<Object[]> VALID = List.of(
        new Object[]{CREATED, VALIDATION_PASSED, VALIDATED},
        new Object[]{CREATED, VALIDATION_FAILED, REJECTED},
        new Object[]{VALIDATED, RESERVATION_SUCCEEDED, INVENTORY_RESERVED},
        ...
        new Object[]{COMPENSATION_PENDING, RELEASED_AFTER_FAILURE, FAILED},
        new Object[]{CREATED, SAGA_EXHAUSTED, FAILED}, ... /* any non-terminal -> FAILED */ );

@ParameterizedTest(name = "{0} -> {1} allowed={2}")
@MethodSource("everyStatusPair")          // 9 x 9 = 81 pairs, expectations from the contract's allowed-pairs table
void statusPairMatrix(OrderStatus from, OrderStatus to, boolean allowed) { ... }

@ParameterizedTest(name = "{0} + {1} = {2} {3}")
@MethodSource("everyStatusAndTrigger")    // 9 x 12 = 108 (status, trigger) pairs: TRANSITION / NO_OP / INVALID
void statusTriggerMatrix(OrderStatus from, OrderTrigger trigger, Kind kind, OrderStatus to) { ... }
```

Plus property-style tests: terminal statuses never move; every non-terminal status can fail when the saga is exhausted;
the set of statuses reachable from each status equals the contract's table; the cancellable statuses are exactly
CREATED, VALIDATED, INVENTORY_RESERVED, CONFIRMED; `require(...)` throws `InvalidStateTransitionException` (409) for
INVALID and returns the status for NO_OP. The new tests call `isAllowed`, `allowedTargets` and `require`, which did not
exist yet.

**Red (compile errors again, the API grew):**

```
[ERROR] .../OrderStateMachineTest.java:[163,37] cannot find symbol
[ERROR] .../OrderStateMachineTest.java:[180,37] cannot find symbol
[ERROR] .../OrderStateMachineTest.java:[211,37] cannot find symbol
[INFO] BUILD FAILURE
```

**Green + refactor in one step:** the switch could not carry 12 triggers readably, so the logic became three tables built
by a tiny fluent builder. `decide` no longer contains any business rule, only a lookup:

```java
on(OrderTrigger.RESERVATION_SUCCEEDED).move(VALIDATED, INVENTORY_RESERVED)
        .alreadyApplied(INVENTORY_RESERVED, CONFIRMED, FULFILLED, COMPENSATION_PENDING);
on(OrderTrigger.CANCEL_REQUESTED)                       // a command, never "already applied" (cancelling twice is a 409)
        .move(CREATED, CANCELLED).move(VALIDATED, CANCELLED)
        .move(INVENTORY_RESERVED, COMPENSATION_PENDING).move(CONFIRMED, COMPENSATION_PENDING);
...
public static Decision decide(OrderStatus from, OrderTrigger trigger) {
    OrderStatus to = TRANSITIONS.get(trigger).get(from);
    if (to != null) return new Decision(Kind.TRANSITION, to);
    if (ALREADY_APPLIED.get(trigger).contains(from)) return new Decision(Kind.NO_OP, from);
    return new Decision(Kind.INVALID, null);
}
```

`isAllowed(from, to)` is DERIVED from the same table (union over triggers), so the status-pair matrix test and the
trigger matrix test check each other.

```
[INFO] Tests run: 207, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.008 s -- in ...OrderStateMachineTest
[INFO] BUILD SUCCESS
```

## Do the tests really catch mistakes? A mutation check

After the suite was green, one table entry was deleted on purpose (the rule "a VALIDATED order may be cancelled"):

```java
-        .move(CREATED, CANCELLED).move(VALIDATED, CANCELLED)
+        .move(CREATED, CANCELLED)
```

```
[ERROR] Tests run: 207, Failures: 5, Errors: 0, Skipped: 0 <<< FAILURE!
[ERROR]   OrderStateMachineTest.cancelBeforeReservationIsImmediate:142      expected: CANCELLED
[ERROR]   OrderStateMachineTest.cancellableStatesAreExactlyThoseOfTheContract:232
[ERROR]   OrderStateMachineTest.everyAllowedPairIsReachableThroughSomeTrigger:205 [targets of VALIDATED]
[ERROR]   OrderStateMachineTest.statusPairMatrix:163                         expected: true
(and one more parameterized case of statusTriggerMatrix)
```

Five independent tests failed for one deleted cell, then the line was restored and the suite is green again. That
redundancy is the point of the two matrices plus the property tests.

## What happened after: refactors that stayed green, and where TDD was not followed

* **Wording of the 409.** When the orchestrator started using `require`, the message read
  "Order cannot transition from FULFILLED to CANCEL_REQUESTED" (a trigger name leaked into a client-visible message).
  `require` now reports the intended status for a cancel (`... to CANCELLED`). Honest note: this one was fixed first and
  asserted afterwards (`OrderSagaOrchestratorTest.cancelOfAFulfilledOrderIsAConflictAndSendsNothing` checks both words),
  a deviation from test-first.
* **The state machine did not change when the orchestrator was built on top of it**, which was the goal of keeping it
  pure: the saga asks `decide`, applies TRANSITION, ignores NO_OP, audits INVALID (events) or turns it into 409 (commands).
* **Integration tests found a design flaw the unit tests could not:** the first full run showed `POST /customers` with an
  empty body returning 400 to a CUSTOMER token (validation ran before `@PreAuthorize`). The fix (URL-level role rules in
  `OrderSecurityConfig`) is covered by the role-matrix test in `OrderSecurityTest`. Same session, same loop: red, fix, green.

## Reproduce

```
JAVA_HOME=/c/Users/sahukari.rao/.jdks/graalvm-jdk-17.0.12 ~/.m2/wrapper/dists/apache-maven-3.9.12-bin/5nmfsn99br87k5d4ajlekdq10k/apache-maven-3.9.12/bin/mvn -B -f services/pom.xml -pl order-service test -Dtest=OrderStateMachineTest
```

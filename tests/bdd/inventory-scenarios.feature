# Inventory service behaviour in Given / When / Then form.
# Executable mirror: services/inventory-service/src/test/java/com/enterprise/oms/inventory/InventoryBddScenariosTest.java
# (one @Nested class per scenario, `given()` / `when()` / `then_*` methods use the same wording as the steps below).
# Contract: docs/architecture/00-shared-contract.md sections 5.2 and 7. All data is synthetic.

Feature: Stock reservation for orders
  As the order saga
  I want stock reserved all-or-nothing and exactly once per order
  So that we never oversell and never lose track of promised stock

  Scenario: Reserve available stock
    Given a product with 10 units on hand and none reserved
    When an order reserves 4 units
    Then the reservation is RESERVED with an expiry
    And 4 units are reserved and 6 are available
    And an InventoryReserved event is queued in the outbox

  Scenario: Refuse a reservation when stock is insufficient
    Given a product with 3 units on hand
    When an order tries to reserve 5 units
    Then the reservation is FAILED with reason INSUFFICIENT_STOCK
    And the shortage reports requested 5 and available 3
    And no stock is reserved
    And an InventoryReservationFailed event is queued in the outbox

  Scenario: Reservations are all-or-nothing across order lines
    Given product A with 10 units and product B with 1 unit
    When an order reserves 5 of A and 2 of B
    Then the reservation is FAILED and only B is reported short
    And product A has nothing reserved

  Scenario: A duplicate reserve request returns the original outcome
    Given an order that already holds a reservation of 4 units
    When the same order asks to reserve again
    Then the original reservation id is returned flagged as duplicate
    And still only 4 units are reserved
    And exactly one reservation exists for the order

  Scenario: The last unit goes to exactly one of two competing orders
    Given a product with 1 unit on hand
    When two orders reserve that unit at the same moment
    Then exactly one reservation is RESERVED and the other is FAILED
    And 1 unit is reserved and never more

  Scenario: A confirmed order keeps its reservation
    Given an order that holds a reservation of 3 units
    When the order is confirmed
    Then the reservation is COMMITTED and no longer expires
    And the 3 units stay reserved

  Scenario: A cancelled order gives its stock back
    Given an order that holds a reservation of 3 units
    When the order is cancelled
    Then the reservation is RELEASED with reason CANCELLATION
    And no units remain reserved
    And an InventoryReleased event is queued in the outbox

  Scenario: An unconfirmed reservation expires
    Given an order that holds a reservation of 3 units whose time to live has elapsed
    When the expiry job runs
    Then the reservation is EXPIRED and the stock is available again
    And an InventoryReleased event with reason EXPIRED is queued in the outbox

  Scenario: A fulfilled order ships its stock
    Given a confirmed order holding 3 of 10 units
    When fulfillment completes
    Then on hand drops to 7 and nothing stays reserved
    And completing fulfillment again changes nothing

  Scenario: Stock cannot be adjusted below what is reserved
    Given a product with 10 units on hand and 8 reserved
    When an operator removes 3 units
    Then the adjustment is rejected as a conflict and on hand stays 10
    And removing 2 units is accepted

  Scenario: Customers see availability but not stock internals
    Given a product with 10 units of which 4 are reserved
    When a customer reads the product
    Then the response shows 6 available
    And it does not contain on-hand, reserved or version
    And an operator reading the same product sees on-hand 10 and reserved 4

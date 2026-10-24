# Order service behaviour scenarios (Gherkin).
#
# Every Scenario below is implemented 1:1 by a JUnit test with the SAME title in
#   services/order-service/src/test/java/com/enterprise/oms/order/bdd/OrderBddScenariosTest.java
# (@Nested = Feature, @DisplayName = Scenario; the id in the tag, e.g. @ORD-A1, is repeated in a comment on the test).
# The scenarios run against the real HTTP layer, JWT security, database and saga of order-service; inventory-service and
# fulfillment-service are played by the test (stubbed validation endpoint and hand-made reply events), so this file
# documents the order-service side of the contract only. All data is synthetic.

Feature: Order management (order-service)

Background:
  Given a customer "Acme" and a product "Widget" costing 12.50 with 100 units in stock

Rule: Order creation

  @ORD-A1
  Scenario: A customer places a valid order and it is confirmed once stock is reserved
    Given the customer is signed in
    When the customer submits an order for 4 widgets
    Then the order is accepted for asynchronous processing
    And the order is CREATED and a saga has started
    When the order is validated against inventory and inventory reserves the stock
    Then the order is CONFIRMED and priced from the validated catalog prices
    And OrderConfirmed and StartFulfillment are published

  @ORD-A2
  Scenario: A customer cannot place an order for another customer
    Given another customer "Globex"
    When Acme submits an order whose customerId is Globex
    Then the request is refused with 403 FORBIDDEN
    And no order exists for Globex

  @ORD-A3
  Scenario: An order with an invalid body is refused with field errors
    When Acme submits an order with quantity 0 and no postal code
    Then the response is 400 VALIDATION_FAILED listing both fields

Rule: Insufficient inventory

  @ORD-B1
  Scenario: Validation finds the product out of stock
    Given a product with only 3 units in stock
    When Acme orders 10 units and the order is validated
    Then the order is REJECTED with the reason naming the product and the stock problem
    And OrderRejected is published and no inventory is reserved

  @ORD-B2
  Scenario: Inventory cannot reserve the requested quantity
    Given a validated order whose stock is taken by someone else before the reservation
    When inventory answers InventoryReservationFailed with a shortage
    Then the order is REJECTED and the customer can see why
    And OrderRejected is published

Rule: Duplicate requests

  @ORD-C1
  Scenario: The same request is submitted twice with the same Idempotency-Key
    Given Acme already submitted an order with key K
    When Acme submits the identical request with key K again (for example after a timeout)
    Then the original order is returned and marked as a replay
    And exactly one order exists

  @ORD-C2
  Scenario: The same Idempotency-Key is reused with a different order
    Given Acme submitted an order for 2 widgets with key K
    When Acme submits an order for 5 widgets with the same key
    Then it is refused with 422 IDEMPOTENCY_KEY_REUSED
    And still only one order exists

  @ORD-C3
  Scenario: Two identical requests arrive at the same moment
    Given two clients send the same request with the same key at the same instant
    When both are processed
    Then both get 202 with the same order, and exactly one order was created

  @ORD-C4
  Scenario: Inventory's reply is delivered twice
    Given a validated order
    When the same InventoryReserved event (same eventId) is delivered twice
    Then the order is confirmed once: one OrderConfirmed and one StartFulfillment

Rule: Cancellation

  @ORD-D1
  Scenario: A customer cancels before the order was validated
    Given a freshly created order
    When the customer cancels it
    Then it is CANCELLED immediately and OrderCancelled is published
    And a later validation does nothing

  @ORD-D2
  Scenario: A customer cancels a confirmed order and the stock is released
    Given a confirmed order
    When the owner cancels it
    Then it waits in COMPENSATION_PENDING while a ReleaseInventory command goes out
    When inventory confirms the release
    Then the order is CANCELLED and OrderCancelled is published

  @ORD-D3
  Scenario: A customer cannot cancel a fulfilled order
    Given a fulfilled order
    When the owner tries to cancel it
    Then the answer is 409 INVALID_STATE_TRANSITION and the order is unchanged

  @ORD-D4
  Scenario: A reservation arrives after the order was cancelled
    Given an order cancelled while its reservation was still in flight
    When the InventoryReserved event arrives late
    Then the order stays CANCELLED and the reservation is released (compensation)

Rule: Failed fulfillment

  @ORD-E1
  Scenario: Fulfillment fails and the reserved stock is given back
    Given a confirmed order
    When fulfillment reports a failure after its own retries
    Then the order enters COMPENSATION_PENDING and ReleaseInventory(FULFILLMENT_FAILURE) is sent
    When inventory confirms the release
    Then the order is FAILED with the reason, OrderFailed is published, and it shows up in the failed list

  @ORD-E2
  Scenario: A stuck compensation is retried by an operator
    Given an order in COMPENSATION_PENDING whose release command got lost
    When an operator retries the order
    Then the idempotent ReleaseInventory command is sent again, the saga log shows the retry
    And when inventory finally answers, the order reaches FAILED

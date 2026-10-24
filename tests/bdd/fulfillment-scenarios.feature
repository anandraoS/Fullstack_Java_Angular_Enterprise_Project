@fulfillment-service
Feature: Fulfillment lifecycle and failure handling
  The fulfillment service consumes StartFulfillment commands, simulates picking, packing and shipping, and
  reports the outcome to the order saga through FulfillmentStarted / FulfillmentCompleted / FulfillmentFailed.
  Executable mirror: services/fulfillment-service/src/test/java/com/enterprise/oms/fulfillment/FulfillmentBddScenariosTest.java

  Background:
    Given the fulfillment service allows 3 internal attempts per fulfillment

  Scenario: Failed fulfillment is retried internally and reported once after the last attempt
    Given a StartFulfillment command for an order shipping to city "FAILTOWN"
    When the command is received
    And the fulfillment scheduler runs until it has nothing left to do
    Then a fulfillment exists for the order with status "FAILED" and 3 attempts
    And its failure reason mentions "FAILTOWN"
    And exactly one "FulfillmentStarted" event and exactly one "FulfillmentFailed" event with attempts 3 are in the outbox for the order
    And no "FulfillmentCompleted" event is in the outbox for the order
    And the failure is recorded in the audit trail

  Scenario: An operator retry of a failed fulfillment once the cause is fixed completes it
    Given a failed fulfillment for an order
    And the simulated failure cause has been fixed
    When an operator retries the fulfillment
    And the fulfillment scheduler runs until it has nothing left to do
    Then the fulfillment has status "COMPLETED" with a deterministic tracking number
    And a "FulfillmentCompleted" event carrying that tracking number is in the outbox

  Scenario: Duplicate StartFulfillment command is a no-op
    Given a fulfillment already exists for an order
    When a second StartFulfillment command arrives for the same order
    Then no second fulfillment is created
    And no additional "FulfillmentStarted" event is written

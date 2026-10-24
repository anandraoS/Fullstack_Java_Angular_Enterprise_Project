@notification-service
Feature: Customer notifications from order and fulfillment events
  The notification service turns order and fulfillment events into simulated customer notifications. It must create
  exactly one notification per event even when Kafka delivers the event more than once, and it must survive a flaky
  (simulated) provider with retries.
  Executable mirror: services/notification-service/src/test/java/com/enterprise/oms/notification/NotificationBddScenariosTest.java

  Scenario: Duplicate event does not create a second notification
    Given an "OrderCreated" event with id E1 for customer "buyer@acme.example.test"
    And the notification service has already processed event E1
    When Kafka delivers event E1 again
    Then exactly one "ORDER_CREATED" notification exists for the order
    And the duplicate is counted
    And only one delivery attempt is made for the order

  Scenario: Replay after the processed-event marker was purged is still protected
    Given an "OrderCreated" event with id E1 has produced a notification
    And the processed-event marker for E1 has been deleted
    When event E1 is replayed
    Then still exactly one "ORDER_CREATED" notification exists for the order

  Scenario: A flaky provider is retried with backoff until the notification is sent
    Given an "OrderCreated" event for a customer at the failing test domain "fail.example.test"
    And the simulated provider fails the first 2 attempts
    When the delivery scheduler runs until nothing is due
    Then the notification is "SENT" after 3 attempts
    And the attempts table shows FAILURE, FAILURE, SUCCESS

  Scenario: A fulfillment event is notified using the contact learned from OrderCreated
    Given an "OrderCreated" event has been processed for an order
    When a "FulfillmentCompleted" event with tracking number "OMS-1234" arrives for the same order
    Then a "FULFILLMENT_COMPLETED" notification for the same customer exists whose subject contains "OMS-1234"

| Module | Unit suites | Unit tests | IT suites | IT tests | Failures+Errors | Skipped | Time (s) |
|---|---:|---:|---:|---:|---:|---:|---:|
| services/fulfillment-service | 6 | 44 | 1 | 1 | 0 | 1 | 63.9 |
| services/inventory-service | 14 | 151 | 3 | 32 | 0 | 19 | 209.2 |
| services/local-stack | 1 | 3 | 0 | 0 | 0 | 0 | 11.5 |
| services/notification-service | 6 | 60 | 1 | 1 | 0 | 1 | 85.8 |
| services/order-service | 24 | 436 | 3 | 27 | 0 | 7 | 232.9 |
| services/platform-common | 14 | 68 | 0 | 0 | 0 | 0 | 115.9 |
| services/platform-test-support | 3 | 5 | 0 | 0 | 0 | 1 | 24.7 |
| gateway | 15 | 142 | 0 | 0 | 0 | 0 | 83.5 |
| **TOTAL** | 83 | **909** | 8 | **61** | **0** | **29** | 827.5 |

Executed (tests - skipped): 941; passed: 941.

Skipped suites (name (count)):
- services/fulfillment-service: FulfillmentPostgresIT (1)
- services/inventory-service: StrategyBenchmarkTest (1)
- services/inventory-service: InventoryLocalPostgresIT (9)
- services/inventory-service: InventoryPostgresIT (9)
- services/notification-service: NotificationPostgresIT (1)
- services/order-service: OrderSearchPostgresIT (7)
- services/platform-test-support: PostgresContainerSmokeTest (1)

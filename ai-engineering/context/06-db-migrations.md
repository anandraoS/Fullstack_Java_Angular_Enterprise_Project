# Database migration rules (Flyway)

Load when: adding or altering a table, index, constraint, or seed data.

* Versioning: `V1..V8` reserved for platform-common (`classpath:db/common`: `outbox_events`, `processed_events`,
  `audit_events`); service schema migrations start at `V10__...`; seed data `V1000__seed_*` in `classpath:db/seed`
  (profile `dev` only). Never edit an applied migration; add a new version.
* Portable SQL: the same files run on PostgreSQL 16 and on H2 in `MODE=PostgreSQL`. Use `VARCHAR`, `NUMERIC(19,2)`,
  `TIMESTAMP`, `UUID`, `BOOLEAN`, `BIGINT`. No `jsonb`, arrays, `SERIAL`, partial indexes, `CREATE INDEX CONCURRENTLY`,
  or PG-only functions in shared migrations. If PG-only is truly needed, use a vendor-specific location and justify in an ADR.
* Expand/contract for zero-downtime (rolling deploys run old and new code together):
  1. Expand: add nullable column / new table / new index (backwards compatible).
  2. Deploy code that writes both and reads new with fallback.
  3. Backfill in batches (separate migration or job), verify.
  4. Contract (drop old) in a LATER release, after rollback window.
  Never rename or drop a column in the same release that stops using it.
* Constraints encode invariants: unique `(order_id)` on reservations, unique `(consumer, event_id)` on processed events,
  unique `(source_event_id, template)` on notifications, `CHECK (on_hand >= 0)`, `CHECK (reserved >= 0)`,
  `CHECK (reserved <= on_hand)` where the DB supports it, foreign keys inside a service only.
* Indexes: every FK and every filter used by a list endpoint (`status`, `customer_id`, `created_at`); partial progress
  queries for the outbox (`published_at IS NULL`) must stay cheap.
* Locking: avoid long `ALTER` on hot tables; add indexes in a window; batch backfills (1k rows, commit each).
* Test: a migration test boots the service against H2 with Flyway `validate`; Testcontainers PostgreSQL variant is skipped
  without Docker (state this honestly).
* Rollback: migrations are forward-only; rollback = deploy previous code, which must still work with the expanded
  schema. Document the data impact in the PR.

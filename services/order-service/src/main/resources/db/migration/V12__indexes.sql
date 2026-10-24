-- Index design (kept in its own migration so the reasoning is easy to review).
--
-- GET /orders is always "newest first" with optional filters, so every index ends in created_at and every filter
-- combination the API offers has a leading-column match:
--   customer view (forced customer filter)          -> ix_orders_customer_created   (customer_id, created_at)
--   customer + status                               -> ix_orders_customer_status_created
--   operator filter by status (+ date range)        -> ix_orders_status_created     (status, created_at)
--   operator date range only / default listing      -> ix_orders_created            (created_at)
-- ORDER BY created_at DESC is served by a backward index scan, so no DESC index variants are needed
-- (and plain indexes stay portable between PostgreSQL and H2).
CREATE INDEX ix_orders_customer_created        ON orders (customer_id, created_at);
CREATE INDEX ix_orders_customer_status_created ON orders (customer_id, status, created_at);
CREATE INDEX ix_orders_status_created          ON orders (status, created_at);
CREATE INDEX ix_orders_created                 ON orders (created_at);
-- failed-orders view: status IN (...) OR (status = COMPENSATION_PENDING AND updated_at < ?) is covered by the status index.
CREATE INDEX ix_orders_updated                 ON orders (updated_at);

CREATE INDEX ix_order_items_order      ON order_items (order_id, line_no);
CREATE INDEX ix_history_order          ON order_status_history (order_id, occurred_at);
CREATE INDEX ix_customers_name         ON customers (name);

-- recovery job: "RUNNING/COMPENSATING sagas not touched since X"
CREATE INDEX ix_saga_state_updated     ON saga_instances (state, updated_at);
-- reconciliation: sagas holding an unreleased reservation
CREATE INDEX ix_saga_reservation       ON saga_instances (reservation_released, state);
CREATE INDEX ix_step_log_order         ON saga_step_log (order_id, id);

-- idempotency housekeeping (delete keys older than oms.idempotency.retention)
CREATE INDEX ix_idempotency_created    ON idempotency_keys (created_at);

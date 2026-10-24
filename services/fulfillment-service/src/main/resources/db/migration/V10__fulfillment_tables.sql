-- fulfillment-service schema (portable: PostgreSQL 16 and H2 in MODE=PostgreSQL).
-- V1..V8 are platform-common (outbox_events, processed_events, audit_events); service migrations start at V10.

CREATE TABLE fulfillments (
    id              UUID         NOT NULL PRIMARY KEY,
    order_id        UUID         NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    tracking_number VARCHAR(40),
    attempts        INTEGER      NOT NULL DEFAULT 0,
    failure_reason  VARCHAR(500),
    ship_line1      VARCHAR(200),
    ship_city       VARCHAR(100),
    ship_postal     VARCHAR(30),
    ship_country    VARCHAR(60),
    item_count      INTEGER      NOT NULL DEFAULT 0,
    correlation_id  VARCHAR(64),
    source_event_id UUID,
    next_step_at    TIMESTAMP WITH TIME ZONE,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_fulfillments_order UNIQUE (order_id)
);
CREATE INDEX ix_fulfillments_status_due ON fulfillments (status, next_step_at);
CREATE INDEX ix_fulfillments_created ON fulfillments (created_at);

CREATE TABLE fulfillment_events (
    id             UUID        NOT NULL PRIMARY KEY,
    fulfillment_id UUID        NOT NULL,
    seq            INTEGER     NOT NULL,
    status         VARCHAR(20) NOT NULL,
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_fulfillment_events_fulfillment FOREIGN KEY (fulfillment_id) REFERENCES fulfillments (id)
);
CREATE INDEX ix_fulfillment_events_ful ON fulfillment_events (fulfillment_id, seq);

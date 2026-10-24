-- platform-common shared tables (portable: PostgreSQL 16 and H2 in MODE=PostgreSQL).
-- Version range V1..V8 is RESERVED for platform-common; seed data uses V1000+; service migrations start at V10.

CREATE TABLE outbox_events (
    id             UUID          NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(64)   NOT NULL,
    aggregate_id   VARCHAR(64)   NOT NULL,
    event_type     VARCHAR(100)  NOT NULL,
    topic          VARCHAR(200)  NOT NULL,
    event_key      VARCHAR(200),
    payload        VARCHAR(20000) NOT NULL,
    headers        VARCHAR(4000),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at   TIMESTAMP WITH TIME ZONE,
    attempts       INTEGER       NOT NULL DEFAULT 0,
    last_error     VARCHAR(1000)
);
CREATE INDEX ix_outbox_pending ON outbox_events (published_at, created_at);
CREATE INDEX ix_outbox_aggregate ON outbox_events (aggregate_type, aggregate_id);

CREATE TABLE processed_events (
    consumer     VARCHAR(100) NOT NULL,
    event_id     UUID         NOT NULL,
    event_type   VARCHAR(100),
    topic        VARCHAR(200),
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_processed_events PRIMARY KEY (consumer, event_id)
);

CREATE TABLE audit_events (
    id             UUID          NOT NULL PRIMARY KEY,
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    actor          VARCHAR(200)  NOT NULL,
    entity_type    VARCHAR(100)  NOT NULL,
    entity_id      VARCHAR(100)  NOT NULL,
    action         VARCHAR(100)  NOT NULL,
    detail         VARCHAR(8000),
    correlation_id VARCHAR(64)
);
CREATE INDEX ix_audit_entity ON audit_events (entity_type, entity_id, occurred_at);
CREATE INDEX ix_audit_time ON audit_events (occurred_at);

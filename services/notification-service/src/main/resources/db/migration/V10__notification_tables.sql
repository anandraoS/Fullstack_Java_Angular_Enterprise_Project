-- notification-service schema (portable: PostgreSQL 16 and H2 in MODE=PostgreSQL).
-- V1..V8 are platform-common (outbox_events [unused here], processed_events, audit_events); service migrations start at V10.

-- Local copy of "who is the customer / where do we send mail for this order", fed from order events (OrderCreated and
-- the other order events that carry customerEmail). Fulfillment events only carry orderId, so they resolve the
-- recipient here. This is the ONLY place the e-mail address is stored (notifications reference it through order_id).
CREATE TABLE order_contacts (
    order_id       UUID         NOT NULL PRIMARY KEY,
    customer_id    UUID         NOT NULL,
    customer_email VARCHAR(320) NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_order_contacts_customer ON order_contacts (customer_id);

CREATE TABLE notifications (
    id              UUID         NOT NULL PRIMARY KEY,
    customer_id     UUID         NOT NULL,
    order_id        UUID         NOT NULL,
    channel         VARCHAR(10)  NOT NULL,
    template        VARCHAR(60)  NOT NULL,
    subject         VARCHAR(300) NOT NULL,
    status          VARCHAR(10)  NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    last_error      VARCHAR(500),
    correlation_id  VARCHAR(64),
    source_event_id UUID         NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at         TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_notifications_event_template UNIQUE (source_event_id, template)
);
CREATE INDEX ix_notifications_customer ON notifications (customer_id, created_at);
CREATE INDEX ix_notifications_order ON notifications (order_id);
CREATE INDEX ix_notifications_due ON notifications (status, next_attempt_at);

CREATE TABLE notification_attempts (
    id              UUID        NOT NULL PRIMARY KEY,
    notification_id UUID        NOT NULL,
    attempt_no      INTEGER     NOT NULL,
    outcome         VARCHAR(10) NOT NULL,
    error           VARCHAR(500),
    attempted_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_notification_attempts_notification FOREIGN KEY (notification_id) REFERENCES notifications (id)
);
CREATE INDEX ix_notification_attempts_notification ON notification_attempts (notification_id, attempted_at);

-- inventory-service schema (portable: PostgreSQL 16 and H2 in MODE=PostgreSQL). V1..V8 = platform-common tables.

CREATE TABLE products (
    id          UUID          NOT NULL PRIMARY KEY,
    sku         VARCHAR(32)   NOT NULL,
    name        VARCHAR(200)  NOT NULL,
    description VARCHAR(2000),
    category    VARCHAR(100)  NOT NULL,
    price       NUMERIC(12,2) NOT NULL,
    currency    VARCHAR(3)    NOT NULL DEFAULT 'USD',
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    version     BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_products_sku UNIQUE (sku),
    CONSTRAINT ck_products_price CHECK (price >= 0)
);
CREATE INDEX ix_products_category ON products (category, active);
CREATE INDEX ix_products_name ON products (name);

-- One stock row per product. `version` is the optimistic-lock token (bumped by every writer, including the atomic
-- conditional UPDATEs). The CHECK constraints are the last line of defence against overselling.
CREATE TABLE stock (
    product_id UUID    NOT NULL PRIMARY KEY,
    on_hand    INTEGER NOT NULL,
    reserved   INTEGER NOT NULL DEFAULT 0,
    version    BIGINT  NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_stock_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_stock_non_negative CHECK (on_hand >= 0 AND reserved >= 0),
    CONSTRAINT ck_stock_reserved_le_on_hand CHECK (reserved <= on_hand)
);

CREATE TABLE inventory_reservations (
    id             UUID         NOT NULL PRIMARY KEY,
    order_id       UUID         NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    expires_at     TIMESTAMP WITH TIME ZONE,
    release_reason VARCHAR(32),
    failure_reason VARCHAR(64),
    failure_detail VARCHAR(4000),
    shipped_at     TIMESTAMP WITH TIME ZONE,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    -- duplicate-command protection: one reservation (of any outcome) per order
    CONSTRAINT uq_reservations_order UNIQUE (order_id),
    CONSTRAINT ck_reservations_status CHECK (status IN ('RESERVED','COMMITTED','RELEASED','EXPIRED','FAILED'))
);
CREATE INDEX ix_reservations_status_expiry ON inventory_reservations (status, expires_at);
CREATE INDEX ix_reservations_created ON inventory_reservations (created_at);

CREATE TABLE inventory_reservation_items (
    id             UUID    NOT NULL PRIMARY KEY,
    reservation_id UUID    NOT NULL,
    product_id     UUID    NOT NULL,
    quantity       INTEGER NOT NULL,
    CONSTRAINT fk_resitems_reservation FOREIGN KEY (reservation_id) REFERENCES inventory_reservations (id),
    CONSTRAINT uq_resitems_line UNIQUE (reservation_id, product_id),
    CONSTRAINT ck_resitems_qty CHECK (quantity > 0)
);
CREATE INDEX ix_resitems_product ON inventory_reservation_items (product_id);

-- Append-only ledger of every stock change (adjustments, reserve, release, expire, ship).
CREATE TABLE stock_movements (
    id             UUID         NOT NULL PRIMARY KEY,
    product_id     UUID         NOT NULL,
    movement_type  VARCHAR(20)  NOT NULL,
    on_hand_delta  INTEGER      NOT NULL,
    reserved_delta INTEGER      NOT NULL,
    on_hand_after  INTEGER,
    reserved_after INTEGER,
    reason         VARCHAR(500),
    reference      VARCHAR(100),
    actor          VARCHAR(200) NOT NULL,
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_movements_product FOREIGN KEY (product_id) REFERENCES products (id)
);
CREATE INDEX ix_movements_product_time ON stock_movements (product_id, occurred_at);
CREATE INDEX ix_movements_reference ON stock_movements (reference);

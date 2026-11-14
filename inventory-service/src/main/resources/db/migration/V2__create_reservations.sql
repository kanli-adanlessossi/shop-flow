-- A reservation is chosen by the caller (order-platform's reservationId), so it is the primary key and a
-- repeated POST with the same id is detected by the unique index. status only moves forward:
-- HELD -> CONFIRMED -> SHIPPED.
CREATE TABLE reservation (
    reservation_id VARCHAR(100) NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (reservation_id),
    CONSTRAINT reservation_status_valid CHECK (status IN ('HELD', 'CONFIRMED', 'SHIPPED'))
);

-- Used by the expiry job (FEAT-004): HELD reservations older than the hold TTL.
CREATE INDEX reservation_status_created_at_idx ON reservation (status, created_at);

CREATE TABLE reservation_line (
    id             BIGINT GENERATED ALWAYS AS IDENTITY,
    reservation_id VARCHAR(100) NOT NULL REFERENCES reservation (reservation_id),
    sku            VARCHAR(64)  NOT NULL,
    quantity       INTEGER      NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT reservation_line_unique_sku UNIQUE (reservation_id, sku),
    CONSTRAINT reservation_line_quantity_positive CHECK (quantity > 0)
);
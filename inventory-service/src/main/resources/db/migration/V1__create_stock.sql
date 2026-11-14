-- Units available per SKU. The CHECK is the last line of defence against overselling: the conditional
-- UPDATE in StockRepository already refuses to go below zero.
CREATE TABLE stock (
    sku       VARCHAR(64) NOT NULL,
    available INTEGER     NOT NULL,
    PRIMARY KEY (sku),
    CONSTRAINT stock_available_not_negative CHECK (available >= 0)
);

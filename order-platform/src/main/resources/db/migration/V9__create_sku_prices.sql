-- The shop's price list. Orders copy the price of each line when they are placed.
create table sku_prices (
    sku        varchar(255) primary key,
    unit_price numeric(12, 2) not null
);

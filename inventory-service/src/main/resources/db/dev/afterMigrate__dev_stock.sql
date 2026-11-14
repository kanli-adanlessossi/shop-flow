-- Development stock for the dev profile (SKUs used by order-platform's price list and load-tests/place-orders.js).
-- Safe to repeat: existing rows keep their current level.
insert into stock (sku, available) values ('SKU-001', 1000) on conflict (sku) do nothing;
insert into stock (sku, available) values ('SKU-002', 1000) on conflict (sku) do nothing;
insert into stock (sku, available) values ('SKU-003', 1000) on conflict (sku) do nothing;
insert into stock (sku, available) values ('SKU-004', 1000) on conflict (sku) do nothing;
insert into stock (sku, available) values ('SKU-005', 1000) on conflict (sku) do nothing;
insert into stock (sku, available) values ('SKU-006', 1000) on conflict (sku) do nothing;
insert into stock (sku, available) values ('SKU-007', 1000) on conflict (sku) do nothing;
insert into stock (sku, available) values ('SKU-008', 1000) on conflict (sku) do nothing;

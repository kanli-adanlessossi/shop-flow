-- Development price list for the dev profile (SKUs used by load-tests/place-orders.js). Safe to repeat.
insert into sku_prices (sku, unit_price)
select 'SKU-001', 5.99
where not exists (select 1 from sku_prices where sku = 'SKU-001');

insert into sku_prices (sku, unit_price)
select 'SKU-002', 10.99
where not exists (select 1 from sku_prices where sku = 'SKU-002');

insert into sku_prices (sku, unit_price)
select 'SKU-003', 15.99
where not exists (select 1 from sku_prices where sku = 'SKU-003');

insert into sku_prices (sku, unit_price)
select 'SKU-004', 20.99
where not exists (select 1 from sku_prices where sku = 'SKU-004');

insert into sku_prices (sku, unit_price)
select 'SKU-005', 25.99
where not exists (select 1 from sku_prices where sku = 'SKU-005');

insert into sku_prices (sku, unit_price)
select 'SKU-006', 30.99
where not exists (select 1 from sku_prices where sku = 'SKU-006');

insert into sku_prices (sku, unit_price)
select 'SKU-007', 35.99
where not exists (select 1 from sku_prices where sku = 'SKU-007');

insert into sku_prices (sku, unit_price)
select 'SKU-008', 40.99
where not exists (select 1 from sku_prices where sku = 'SKU-008');

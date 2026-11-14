-- Prices for the SKUs the tests order. Flyway callback: runs after every migrate, safe to repeat.
insert into sku_prices (sku, unit_price)
select 'A', 10.50
where not exists (select 1 from sku_prices where sku = 'A');

insert into sku_prices (sku, unit_price)
select 'B', 3.25
where not exists (select 1 from sku_prices where sku = 'B');

insert into sku_prices (sku, unit_price)
select 'C', 0.50
where not exists (select 1 from sku_prices where sku = 'C');

insert into sku_prices (sku, unit_price)
select 'POISON', 66.66
where not exists (select 1 from sku_prices where sku = 'POISON');

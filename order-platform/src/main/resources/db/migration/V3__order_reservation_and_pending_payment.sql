-- Stock is now reserved before the order is saved, so the order cannot be the reservation's key
-- (its id does not exist yet). Each order stores the key it reserved stock under.
-- Orders created before this change reserved under their own id, so that is the backfill.
alter table orders add column reservation_id varchar(64);
update orders set reservation_id = cast(id as varchar(64));
alter table orders alter column reservation_id set not null;
create unique index ux_orders_reservation_id on orders (reservation_id);

-- PENDING was renamed to PENDING_PAYMENT.
update orders set status = 'PENDING_PAYMENT' where status = 'PENDING';

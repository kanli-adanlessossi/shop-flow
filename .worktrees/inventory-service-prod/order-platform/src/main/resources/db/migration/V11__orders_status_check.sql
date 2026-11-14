-- Only the values of the OrderStatus enum may be stored; a bad status written outside the application
-- (or by a bug) is refused by the database instead of failing later when the row is read.
-- CASE instead of "status in (...)" or "a or b" on purpose: H2 2.4 cannot evaluate a check made of
-- literal comparisons joined by OR once the connection that ran the migration is closed
-- ("Check constraint invalid"), which breaks the H2-based repository tests.
alter table orders add constraint ck_orders_status
    check (case status
               when 'PENDING_PAYMENT' then 1
               when 'CONFIRMED' then 1
               when 'SHIPPED' then 1
               when 'CANCELLED' then 1
               else 0
           end = 1);

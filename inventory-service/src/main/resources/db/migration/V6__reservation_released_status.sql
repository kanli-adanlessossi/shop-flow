-- A released reservation now stays as a RELEASED row (its lines are deleted) so that a late repeat of the
-- original reserve call with the same id is refused instead of taking the stock a second time.
-- The expiry job deletes RELEASED rows after shopflow.inventory.processed-events-retention.
ALTER TABLE reservation DROP CONSTRAINT reservation_status_valid;
ALTER TABLE reservation ADD CONSTRAINT reservation_status_valid
    CHECK (status IN ('HELD', 'CONFIRMED', 'SHIPPED', 'RELEASED'));

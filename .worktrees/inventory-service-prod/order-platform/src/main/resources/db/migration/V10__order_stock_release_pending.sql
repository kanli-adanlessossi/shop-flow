-- The unpaid-order job cancels an order first and releases its stock afterwards. This flag marks a
-- cancelled order whose release inventory has not confirmed yet, so the job can retry it.
alter table orders add column stock_release_pending boolean not null default false;

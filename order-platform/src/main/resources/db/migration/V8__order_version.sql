-- Optimistic locking: a concurrent change to the same order fails instead of silently overwriting it.
alter table orders add column version bigint not null default 0;

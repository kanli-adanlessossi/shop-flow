-- Inbox of event ids this application has already handled, per consumer (Kafka group).
-- Kafka delivers at least once, so a handler first records the event id here in its own
-- transaction; a second delivery of the same event hits the primary key and is skipped.
-- Rows older than shopflow.processed-events-retention are purged by a scheduled job.
create table processed_events (
    consumer     varchar(64) not null,
    event_id     varchar(64) not null,
    processed_at timestamp(6) with time zone not null,
    primary key (consumer, event_id)
);

-- The purge job deletes by age.
create index idx_processed_events_processed_at on processed_events (processed_at);

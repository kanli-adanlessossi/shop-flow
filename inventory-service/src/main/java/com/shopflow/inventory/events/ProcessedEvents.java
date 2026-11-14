package com.shopflow.inventory.events;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The inbox: which events a consumer has already handled. Call {@link #markProcessed} inside the
 * transaction that does the work. If the work fails and the transaction rolls back, the mark is
 * rolled back with it, so the redelivered event is handled again. If two deliveries of one event
 * race, the primary key lets only one commit; the other fails and is retried, and then sees the mark.
 */
@Component
public class ProcessedEvents {

	private final JdbcClient jdbc;

	private final Clock clock;

	public ProcessedEvents(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** Returns true if this is the first time the consumer sees the event, false if it is a repeat. */
	public boolean markProcessed(String consumer, String eventId) {
		// Plain SQL that runs on PostgreSQL and H2. The timestamp is bound as OffsetDateTime
		// because the PostgreSQL driver cannot bind java.time.Instant.
		int inserted = this.jdbc.sql("""
				insert into processed_events (consumer, event_id, processed_at)
				select :consumer, :eventId, :processedAt
				where not exists (
				    select 1 from processed_events where consumer = :consumer and event_id = :eventId)
				""")
				.param("consumer", consumer)
				.param("eventId", eventId)
				.param("processedAt", OffsetDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC))
				.update();
		return inserted == 1;
	}

	/** Removes marks older than the cutoff and returns how many. */
	public int deleteProcessedBefore(Instant cutoff) {
		return this.jdbc.sql("delete from processed_events where processed_at < :cutoff")
				.param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
				.update();
	}

}

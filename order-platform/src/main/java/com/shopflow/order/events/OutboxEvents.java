package com.shopflow.order.events;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Where business code records an event to be published. {@link #add} writes a row into the caller's
 * own transaction (it refuses to run outside one), so the event is stored if and only if the change
 * that caused it is committed. {@link OutboxRelay} sends it to Kafka afterwards.
 */
@Component
public class OutboxEvents {

	/**
	 * The column holds at most this many characters of JSON. An event over the limit makes {@link #add}
	 * throw, which rolls back the business change that caused it. That is deliberate: the change and its
	 * event are saved together or not at all, and a refused event is a bug to fix (shorten the payload or
	 * widen the column), not something to drop silently. Today's events are a few hundred characters.
	 */
	static final int MAX_PAYLOAD_LENGTH = 4000;

	private final JdbcClient jdbc;

	private final JsonMapper mapper;

	private final Clock clock;

	public OutboxEvents(JdbcClient jdbc, JsonMapper mapper, Clock clock) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.clock = clock;
	}

	/** Stores a new event for the payload and returns its envelope. Must be called inside a transaction. */
	@Transactional(propagation = Propagation.MANDATORY)
	public EventEnvelope add(Payload payload) {
		EventEnvelope envelope = EventEnvelope.of(payload);
		String json = this.mapper.writeValueAsString(envelope);
		if (json.length() > MAX_PAYLOAD_LENGTH) {
			throw new IllegalArgumentException("Event " + envelope.type() + " is " + json.length()
					+ " characters as JSON; the outbox holds at most " + MAX_PAYLOAD_LENGTH);
		}
		// Bound as OffsetDateTime: the PostgreSQL driver cannot bind java.time.Instant.
		this.jdbc.sql("""
				insert into outbox_events (event_id, message_key, payload, created_at)
				values (:eventId, :key, :payload, :createdAt)
				""")
				.param("eventId", envelope.eventId())
				.param("key", payload.key())
				.param("payload", json)
				.param("createdAt", OffsetDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC))
				.update();
		return envelope;
	}

}

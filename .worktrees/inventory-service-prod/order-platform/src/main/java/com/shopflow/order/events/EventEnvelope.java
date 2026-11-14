package com.shopflow.order.events;

import static com.fasterxml.jackson.annotation.JsonTypeInfo.As.EXTERNAL_PROPERTY;
import static com.fasterxml.jackson.annotation.JsonTypeInfo.Id.CUSTOM;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import tools.jackson.databind.annotation.JsonTypeIdResolver;

/**
 * What every message on {@code order-events} looks like:
 *
 * <pre>{@code
 * {"eventId":"…","occurredAt":"2026-11-12T16:23:21.113312Z",
 *  "payload":{"orderId":1300,"customerId":"cust-7","total":24.25},"type":"order.placed"}
 * }</pre>
 *
 * <p>{@code type} is the name of the payload kind. It is written next to the payload (Jackson's
 * external type id), so there is no separate field to keep in sync: the payload class decides it.
 * Reading is the reverse: {@code type} picks the payload class. An unknown type becomes an
 * {@link UnknownPayload} instead of an error.
 *
 * <p>Rules for changing events: add optional fields only; never rename, remove or change the
 * meaning of one. A breaking change gets a new type name (for example {@code order.placed.v2}).
 *
 * @param eventId unique per event; consumers use it to ignore a message they already handled
 * @param occurredAt when it happened (UTC), not when it was sent
 * @param payload the type-specific data
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
		String eventId,
		Instant occurredAt,
		@JsonTypeInfo(use = CUSTOM, include = EXTERNAL_PROPERTY, property = "type")
		@JsonTypeIdResolver(PayloadTypeIdResolver.class)
		Payload payload) {

	/** A message missing any of these is not an envelope; failing here makes the consumer skip it. */
	public EventEnvelope {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(occurredAt, "occurredAt");
		Objects.requireNonNull(payload, "payload");
	}

	public static EventEnvelope of(Payload payload) {
		return new EventEnvelope(UUID.randomUUID().toString(), Instant.now(), payload);
	}

	/** The event type name, taken from the payload. */
	@JsonIgnore
	public String type() {
		return this.payload.type();
	}

}

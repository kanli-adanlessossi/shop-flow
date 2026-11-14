package com.shopflow.inventory.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * The type-specific part of an {@link EventEnvelope}. Each permitted record carries
 * {@code @JsonTypeName}, which is the {@code type} written next to the payload in the JSON; the
 * mapping back from a type name to a class is in {@link EventEnvelope}.
 */
public sealed interface Payload permits OrderConfirmed, OrderShipped, UnknownPayload {

	/** The event type name, for example {@code order.placed}. */
	@JsonIgnore
	default String type() {
		JsonTypeName name = getClass().getAnnotation(JsonTypeName.class);
		if (name == null) {
			throw new IllegalStateException(getClass().getName() + " has no @JsonTypeName");
		}
		return name.value();
	}

	/** The Kafka message key. Events with the same key go to one partition and stay in order. */
	@JsonIgnore
	String key();

}

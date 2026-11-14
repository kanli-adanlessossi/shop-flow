package com.shopflow.inventory.events;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What a consumer gets for an event type it does not know yet, such as one added by a newer
 * producer. Consumers log it and move on instead of failing the message.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class UnknownPayload implements Payload {

	private final Map<String, Object> fields = new java.util.LinkedHashMap<>();

	@JsonAnySetter
	void keep(String name, Object value) {
		this.fields.put(name, value);
	}

	@JsonIgnore
	public Map<String, Object> fields() {
		return Map.copyOf(this.fields);
	}

	@Override
	@JsonIgnore
	public String type() {
		return "unknown";
	}

	/** Never published, so there is no key. */
	@Override
	public String key() {
		throw new UnsupportedOperationException("An unknown payload cannot be published");
	}

}

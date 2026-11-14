package com.shopflow.inventory.events;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * The payment for an order was confirmed. Only the fields inventory-service needs are read; the others
 * (customer, total) are ignored. {@code reservationId} is null in messages written before the field
 * existed or for an order without a reservation; the handler skips those.
 */
@JsonTypeName("order.confirmed")
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderConfirmed(long orderId, @Nullable String reservationId) implements Payload {

	@Override
	public String key() {
		return String.valueOf(this.orderId);
	}
}
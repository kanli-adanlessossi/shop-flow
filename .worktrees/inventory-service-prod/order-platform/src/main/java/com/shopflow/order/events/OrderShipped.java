package com.shopflow.order.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * A confirmed order was handed over for delivery. inventory-service uses it to turn the order's
 * reservation into shipped stock, which can no longer be released.
 */
@JsonTypeName("order.shipped")
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderShipped(long orderId, String reservationId) implements Payload {

	@Override
	public String key() {
		return String.valueOf(this.orderId);
	}
}

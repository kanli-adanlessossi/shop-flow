package com.shopflow.order.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeName;

/** An order was cancelled and its stock given back. {@code reason} is free text, e.g. "unpaid". */
@JsonTypeName("order.cancelled")
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCancelled(long orderId, String customerId, String reason) implements Payload {

	@Override
	public String key() {
		return String.valueOf(this.orderId);
	}

}

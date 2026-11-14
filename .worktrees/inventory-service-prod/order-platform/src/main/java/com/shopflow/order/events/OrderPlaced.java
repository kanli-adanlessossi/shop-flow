package com.shopflow.order.events;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeName;

/** An order was saved and its stock reserved; it now waits for payment. */
@JsonTypeName("order.placed")
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPlaced(long orderId, String customerId, BigDecimal total) implements Payload {

	@Override
	public String key() {
		return String.valueOf(this.orderId);
	}

}

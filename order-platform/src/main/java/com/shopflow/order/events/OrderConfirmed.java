package com.shopflow.order.events;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeName;

/** The payment provider confirmed the payment for an order. {@code total} is the amount that was paid. */
@JsonTypeName("order.confirmed")
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderConfirmed(long orderId, String customerId, BigDecimal total) implements Payload {

	@Override
	public String key() {
		return String.valueOf(this.orderId);
	}

}

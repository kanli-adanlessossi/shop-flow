package com.shopflow.order.events;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * The payment provider confirmed the payment for an order. {@code total} is the amount that was paid.
 * {@code reservationId} is the stock reservation of the order, so inventory can mark it confirmed; it is
 * optional so messages written before the field existed still read.
 */
@JsonTypeName("order.confirmed")
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderConfirmed(long orderId, String customerId, BigDecimal total,
		@Nullable String reservationId) implements Payload {

	@Override
	public String key() {
		return String.valueOf(this.orderId);
	}
}

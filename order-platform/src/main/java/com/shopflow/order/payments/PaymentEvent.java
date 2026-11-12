package com.shopflow.order.payments;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The body of a payment provider webhook (shape invented for this project):
 *
 * <pre>{@code
 * {"eventId":"evt_123","type":"payment.succeeded",
 *  "data":{"orderId":42,"paymentId":"pay_9","amount":24.25}}
 * }</pre>
 *
 * Everything is nullable here because the body comes from outside and is checked by the caller;
 * unknown fields are ignored so the provider can add some.
 *
 * @param eventId the provider's id for this delivery's event; stays the same when the provider retries
 * @param type e.g. {@code payment.succeeded} or {@code payment.failed}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentEvent(@Nullable String eventId, @Nullable String type, @Nullable Data data) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Data(@Nullable Long orderId, @Nullable String paymentId, @Nullable BigDecimal amount) {
	}

}

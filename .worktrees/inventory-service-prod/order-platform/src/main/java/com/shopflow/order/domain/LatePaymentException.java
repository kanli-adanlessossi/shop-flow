package com.shopflow.order.domain;

/**
 * A payment arrived for an order that was already cancelled (usually because it was not paid in time and
 * its stock was given back). The order cannot be confirmed, and the customer has paid: this needs a refund.
 */
public class LatePaymentException extends RuntimeException {

	private final String orderId;

	public LatePaymentException(String orderId) {
		super("Payment received for order " + orderId + ", which was already cancelled");
		this.orderId = orderId;
	}

	public String getOrderId() {
		return this.orderId;
	}

}

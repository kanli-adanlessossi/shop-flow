package com.shopflow.order.domain;

import java.math.BigDecimal;

/** The amount the provider says was paid is not the order's total, so the order is not confirmed. */
public class PaymentAmountMismatchException extends RuntimeException {

	private final String orderId;

	public PaymentAmountMismatchException(String orderId, BigDecimal expected, BigDecimal paid) {
		super("Order " + orderId + " is for " + expected + " but the payment is for " + paid);
		this.orderId = orderId;
	}

	public String getOrderId() {
		return this.orderId;
	}

}

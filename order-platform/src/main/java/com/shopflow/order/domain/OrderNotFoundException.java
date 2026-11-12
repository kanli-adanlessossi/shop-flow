package com.shopflow.order.domain;

/**
 * Thrown when an order cannot be found by its id.
 */
public class OrderNotFoundException extends RuntimeException {

	private final String orderId;

	public OrderNotFoundException(String orderId) {
		super("Order " + orderId + " not found");
		this.orderId = orderId;
	}

	public String getOrderId() {
		return this.orderId;
	}

}

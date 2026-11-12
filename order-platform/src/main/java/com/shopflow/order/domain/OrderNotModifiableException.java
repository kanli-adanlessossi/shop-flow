package com.shopflow.order.domain;

/**
 * Thrown when an order is in a status that does not allow the requested change.
 */
public class OrderNotModifiableException extends RuntimeException {

	private final String orderId;

	private final OrderStatus status;

	public OrderNotModifiableException(String orderId, OrderStatus status, String action) {
		super("Order " + orderId + " cannot be " + action + " while it is " + status);
		this.orderId = orderId;
		this.status = status;
	}

	public String getOrderId() {
		return this.orderId;
	}

	public OrderStatus getStatus() {
		return this.status;
	}

}

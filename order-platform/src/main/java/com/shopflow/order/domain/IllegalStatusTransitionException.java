package com.shopflow.order.domain;

/**
 * Thrown when an order is asked to move to a status it cannot reach from its current one.
 */
public class IllegalStatusTransitionException extends RuntimeException {

	private final String orderId;

	private final OrderStatus from;

	private final OrderStatus to;

	public IllegalStatusTransitionException(String orderId, OrderStatus from, OrderStatus to) {
		super("Order " + orderId + " cannot move from " + from + " to " + to);
		this.orderId = orderId;
		this.from = from;
		this.to = to;
	}

	public String getOrderId() {
		return this.orderId;
	}

	public OrderStatus getFrom() {
		return this.from;
	}

	public OrderStatus getTo() {
		return this.to;
	}

}

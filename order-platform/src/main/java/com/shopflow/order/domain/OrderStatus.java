package com.shopflow.order.domain;

public enum OrderStatus {
	/** Stock is reserved and the order is saved; waiting for the customer to pay. */
	PENDING_PAYMENT,
	PAID,
	SHIPPED,
	CANCELLED
}

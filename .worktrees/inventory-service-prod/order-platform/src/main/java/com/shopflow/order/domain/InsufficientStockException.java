package com.shopflow.order.domain;

/**
 * Thrown when the inventory refuses a reservation because there is not enough stock.
 */
public class InsufficientStockException extends RuntimeException {

	public InsufficientStockException() {
		super("Not enough stock to fulfil the order");
	}

}

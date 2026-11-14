package com.shopflow.inventory.domain;

/** At least one line of a reservation asks for more than is available, or for an unknown SKU. */
public class InsufficientStockException extends RuntimeException {

	private final String sku;

	public InsufficientStockException(String sku) {
		super("Not enough stock for " + sku);
		this.sku = sku;
	}

	public String getSku() {
		return this.sku;
	}

}

package com.shopflow.order.domain;

/**
 * Thrown when an order names a SKU that has no price in the shop's price list.
 */
public class UnknownSkuException extends RuntimeException {

	private final String sku;

	public UnknownSkuException(String sku) {
		super("Unknown SKU " + sku);
		this.sku = sku;
	}

	public String getSku() {
		return this.sku;
	}

}

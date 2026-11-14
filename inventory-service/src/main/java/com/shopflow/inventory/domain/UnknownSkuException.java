package com.shopflow.inventory.domain;

/** A stock lookup for a SKU that has no stock row. */
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

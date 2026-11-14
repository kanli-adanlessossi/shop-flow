package com.shopflow.inventory.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Units of one SKU that can still be reserved. Read-only for the application: the level changes
 * only through the conditional UPDATEs in {@link StockRepository}, never by setting a field.
 */
@Entity
@Table(name = "stock")
public class Stock {

	@Id
	private String sku;

	private int available;

	/** For JPA only. */
	protected Stock() {
		this.sku = "";
	}

	public String getSku() {
		return this.sku;
	}

	public int getAvailable() {
		return this.available;
	}

}

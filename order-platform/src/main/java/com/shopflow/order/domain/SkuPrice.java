package com.shopflow.order.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The current price of one unit of a SKU. Orders copy it into their lines when they are placed. */
@Entity
@Table(name = "sku_prices")
public class SkuPrice {

	@Id
	private String sku;

	@Column(nullable = false, precision = 12, scale = 2)
	private BigDecimal unitPrice;

	/** For JPA only. */
	protected SkuPrice() {
		this.sku = "";
		this.unitPrice = BigDecimal.ZERO;
	}

	public SkuPrice(String sku, BigDecimal unitPrice) {
		this.sku = sku;
		this.unitPrice = unitPrice;
	}

	public String getSku() {
		return this.sku;
	}

	public BigDecimal getUnitPrice() {
		return this.unitPrice;
	}

}

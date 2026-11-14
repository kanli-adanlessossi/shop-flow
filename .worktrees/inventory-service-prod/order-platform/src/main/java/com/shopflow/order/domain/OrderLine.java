package com.shopflow.order.domain;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_lines")
public class OrderLine {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private @Nullable Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "order_id", nullable = false)
	private @Nullable Order order;

	@Column(nullable = false)
	private String sku;

	@Column(nullable = false)
	private int quantity;

	/** Price of one unit when the order was placed. */
	@Column(nullable = false, precision = 12, scale = 2)
	private BigDecimal unitPrice;

	/** For JPA only. */
	protected OrderLine() {
		this.sku = "";
		this.unitPrice = BigDecimal.ZERO;
	}

	public OrderLine(String sku, int quantity, BigDecimal unitPrice) {
		this.sku = sku;
		this.quantity = quantity;
		this.unitPrice = unitPrice;
	}

	public @Nullable Long getId() {
		return this.id;
	}

	public @Nullable Order getOrder() {
		return this.order;
	}

	void setOrder(@Nullable Order order) {
		this.order = order;
	}

	public String getSku() {
		return this.sku;
	}

	public int getQuantity() {
		return this.quantity;
	}

	public BigDecimal getUnitPrice() {
		return this.unitPrice;
	}

}

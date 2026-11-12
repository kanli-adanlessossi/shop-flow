package com.shopflow.order.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jspecify.annotations.Nullable;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * An order placed by a customer. Lines are owned by the order: they are saved
 * and deleted with it, and a line removed from the order is deleted too.
 */
@Entity
@Table(name = "orders") // "order" is a reserved word in SQL
public class Order {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private @Nullable Long id;

	@Column(nullable = false)
	private String customerId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OrderStatus status = OrderStatus.PENDING_PAYMENT;

	/** The key the order's stock was reserved under at inventory-service. Never changes. */
	@Column(nullable = false, updatable = false)
	private String reservationId;

	private @Nullable String discountCode;

	@Column(nullable = false, updatable = false)
	// Truncated to the precision the database stores, so a response built before the first
	// reload shows the same value as every later read.
	private Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<OrderLine> lines = new ArrayList<>();

	/** For JPA only. */
	protected Order() {
		this.customerId = "";
		this.reservationId = "";
	}

	public Order(String customerId, @Nullable String discountCode, String reservationId) {
		this.customerId = customerId;
		this.discountCode = discountCode;
		this.reservationId = reservationId;
	}

	/** Only an order waiting for payment can have its content replaced. */
	public boolean canBeModified() {
		return this.status == OrderStatus.PENDING_PAYMENT;
	}

	/** Orders that were never paid, or were cancelled, can be removed. */
	public boolean canBeDeleted() {
		return this.status == OrderStatus.PENDING_PAYMENT || this.status == OrderStatus.CANCELLED;
	}

	/** Replaces the customer, discount code and all lines. Removed lines are deleted (orphan removal). */
	public void replaceContent(String customerId, @Nullable String discountCode, List<OrderLine> newLines) {
		this.customerId = customerId;
		this.discountCode = discountCode;
		this.lines.clear();
		newLines.forEach(this::addLine);
	}

	public void addLine(OrderLine line) {
		line.setOrder(this);
		this.lines.add(line);
	}

	public void removeLine(OrderLine line) {
		if (this.lines.remove(line)) {
			line.setOrder(null);
		}
	}

	public @Nullable Long getId() {
		return this.id;
	}

	public String getCustomerId() {
		return this.customerId;
	}

	public String getReservationId() {
		return this.reservationId;
	}

	public OrderStatus getStatus() {
		return this.status;
	}

	public void setStatus(OrderStatus status) {
		this.status = status;
	}

	public @Nullable String getDiscountCode() {
		return this.discountCode;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public List<OrderLine> getLines() {
		return Collections.unmodifiableList(this.lines);
	}

}

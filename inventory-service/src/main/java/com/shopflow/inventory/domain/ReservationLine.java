package com.shopflow.inventory.domain;

import org.jspecify.annotations.Nullable;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Quantity of one SKU held by a reservation. A SKU appears once per reservation. */
@Entity
@Table(name = "reservation_line")
public class ReservationLine {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private @Nullable Long id;

	private String reservationId;

	private String sku;

	private int quantity;

	/** For JPA only. */
	protected ReservationLine() {
		this.reservationId = "";
		this.sku = "";
	}

	public ReservationLine(String reservationId, String sku, int quantity) {
		this.reservationId = reservationId;
		this.sku = sku;
		this.quantity = quantity;
	}

	public String getReservationId() {
		return this.reservationId;
	}

	public String getSku() {
		return this.sku;
	}

	public int getQuantity() {
		return this.quantity;
	}

}

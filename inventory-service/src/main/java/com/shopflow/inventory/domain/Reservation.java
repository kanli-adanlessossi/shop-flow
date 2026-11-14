package com.shopflow.inventory.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Stock set aside under a caller-chosen id. Created by a native INSERT in {@link ReservationRepository}
 * (so a repeat of the same id is a no-op); this entity is for reading and deleting.
 */
@Entity
@Table(name = "reservation")
public class Reservation {

	@Id
	private String reservationId;

	@Enumerated(EnumType.STRING)
	private ReservationStatus status;

	private Instant createdAt;

	private Instant updatedAt;

	/** For JPA only. */
	protected Reservation() {
		this.reservationId = "";
		this.status = ReservationStatus.HELD;
		this.createdAt = Instant.EPOCH;
		this.updatedAt = Instant.EPOCH;
	}

	public String getReservationId() {
		return this.reservationId;
	}

	public ReservationStatus getStatus() {
		return this.status;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

}

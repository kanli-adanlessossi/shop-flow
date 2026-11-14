package com.shopflow.inventory.domain;

/** A reservation with this id was already released, so the id cannot be used to reserve again. */
public class ReservationReleasedException extends RuntimeException {

	private final String reservationId;

	public ReservationReleasedException(String reservationId) {
		super("Reservation " + reservationId + " was released; use a new reservation id");
		this.reservationId = reservationId;
	}

	public String getReservationId() {
		return this.reservationId;
	}

}

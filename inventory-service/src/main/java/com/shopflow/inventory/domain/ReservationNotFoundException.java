package com.shopflow.inventory.domain;

/** No reservation exists under this id (never made, already released, or expired). */
public class ReservationNotFoundException extends RuntimeException {

	private final String reservationId;

	public ReservationNotFoundException(String reservationId) {
		super("Reservation " + reservationId + " not found");
		this.reservationId = reservationId;
	}

	public String getReservationId() {
		return this.reservationId;
	}

}

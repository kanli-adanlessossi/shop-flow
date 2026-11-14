package com.shopflow.inventory.domain;

/** The goods of this reservation have shipped, so its stock cannot be given back. */
public class ReservationShippedException extends RuntimeException {

	private final String reservationId;

	public ReservationShippedException(String reservationId) {
		super("Reservation " + reservationId + " has shipped and cannot be released");
		this.reservationId = reservationId;
	}

	public String getReservationId() {
		return this.reservationId;
	}

}

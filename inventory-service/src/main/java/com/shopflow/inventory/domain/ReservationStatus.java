package com.shopflow.inventory.domain;

/** Where a reservation is in the order's life. It only moves forward: HELD, CONFIRMED, SHIPPED. */
public enum ReservationStatus {

	/** Stock is set aside; the order is not paid yet. Expires after the hold TTL. */
	HELD,

	/** The order is paid. Never expires; can still be released (the order may be refunded). */
	CONFIRMED,

	/** The goods left. The stock is gone for good, so the reservation can no longer be released. */
	SHIPPED,

	/** The stock was given back. Only a marker, kept for a while so the same id cannot reserve again. */
	RELEASED

}

package com.shopflow.order.domain;

/** What a payment confirmation did to an order. Every value is a success for the webhook caller. */
public enum PaymentOutcome {
	/** The order was waiting for payment and is now CONFIRMED. */
	CONFIRMED,
	/** The order was already CONFIRMED (or further along); nothing changed. */
	ALREADY_CONFIRMED,
	/** This payment event was handled before; nothing changed. */
	DUPLICATE
}

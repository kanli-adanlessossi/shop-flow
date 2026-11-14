package com.shopflow.order.domain;

/**
 * Thrown when the inventory cannot be asked (timeout, connection failure, server error)
 * or answers in a way this service does not understand.
 */
public class InventoryUnavailableException extends RuntimeException {

	public InventoryUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}

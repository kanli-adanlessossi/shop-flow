package com.shopflow.order.inventory;

/**
 * Base type for errors reported by inventory-service, raised by the client's default status
 * handler. Callers can catch the subtype they care about or this base type.
 */
public abstract class InventoryException extends RuntimeException {

	private final int status;

	protected InventoryException(String message, int status) {
		super(message);
		this.status = status;
	}

	/** The HTTP status inventory-service answered with. */
	public int getStatus() {
		return this.status;
	}

	/** 404: the SKU (or reservation) is not known to the inventory. */
	public static class NotFound extends InventoryException {

		public NotFound(String message, int status) {
			super(message, status);
		}

	}

	/** 409: the request conflicts with the stock on hand. */
	public static class Conflict extends InventoryException {

		public Conflict(String message, int status) {
			super(message, status);
		}

	}

	/** Any other 4xx: this service sent something inventory-service rejects. */
	public static class BadRequest extends InventoryException {

		public BadRequest(String message, int status) {
			super(message, status);
		}

	}

	/** 5xx: inventory-service failed. */
	public static class ServerError extends InventoryException {

		public ServerError(String message, int status) {
			super(message, status);
		}

	}

}

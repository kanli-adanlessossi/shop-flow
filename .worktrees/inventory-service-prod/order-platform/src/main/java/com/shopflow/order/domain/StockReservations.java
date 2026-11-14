package com.shopflow.order.domain;

import java.util.List;

/**
 * Port to whatever holds the stock. Reservations are identified by a key chosen by the caller, so
 * stock can be reserved before the order that needs it exists. Implementations translate their own
 * failures into {@link InsufficientStockException} and {@link InventoryUnavailableException}.
 */
public interface StockReservations {

	void reserve(String reservationId, List<OrderDraft.Line> lines);

	/**
	 * Gives back the stock held under a reservation key. Must be safe to repeat: releasing a key
	 * that has nothing reserved (or was already released) succeeds.
	 */
	void release(String reservationId);

}

package com.shopflow.order.inventory;

import java.util.List;

/** {@code reservationId} is chosen by order-platform; inventory-service should treat a repeat as the same reservation. */
public record ReservationRequest(String reservationId, List<Line> lines) {

	public record Line(String sku, int quantity) {
	}

}

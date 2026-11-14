package com.shopflow.order.inventory;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Declarative HTTP client for the inventory-service stock API. The proxy is
 * created in {@link InventoryClientConfig}.
 */
@HttpExchange("/api/stock")
public interface InventoryClient {

	/** Current stock level for one SKU. */
	@GetExchange("/{sku}")
	StockLevel getStock(@PathVariable String sku);

	/** Reserves stock for an order. */
	@PostExchange("/reservations")
	Reservation reserve(@RequestBody ReservationRequest request);

	/** Releases whatever is held under a reservation key. 404 means there is nothing to release. */
	@DeleteExchange("/reservations/{reservationId}")
	void release(@PathVariable String reservationId);

}

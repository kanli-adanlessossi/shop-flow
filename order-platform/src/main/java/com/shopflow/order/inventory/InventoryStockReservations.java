package com.shopflow.order.inventory;

import java.util.List;

import org.springframework.boot.http.client.FilteredHostException;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import com.shopflow.order.domain.InsufficientStockException;
import com.shopflow.order.domain.InventoryUnavailableException;
import com.shopflow.order.domain.OrderDraft;
import com.shopflow.order.domain.StockReservations;

/**
 * Reserves and releases stock through {@link InventoryClient} and maps HTTP failures to domain
 * exceptions. The concurrency cap is on the class so reserve and release share it.
 */
@ConcurrencyLimit(limitString = "${shopflow.inventory.max-concurrent-requests:10}")
@Component
class InventoryStockReservations implements StockReservations {

	private final InventoryClient client;

	InventoryStockReservations(InventoryClient client) {
		this.client = client;
	}

	/**
	 * Retried only when inventory could not be asked (see {@link InventoryUnavailableException}),
	 * never for {@link InsufficientStockException}. A retry reuses the same reservation id, so it
	 * relies on inventory-service treating a repeated reservation with that id as the same one.
	 *
	 * <p>This runs while a customer waits, so retrying stops after {@code timeout} ms in total:
	 * with the 500 ms read timeout that allows about two attempts, and a hung inventory fails the
	 * request in roughly 1.2 s. Releasing stock runs in the background and keeps the longer policy.
	 */
	@Retryable(includes = InventoryUnavailableException.class, maxRetries = 3, delay = 200, multiplier = 2,
			jitter = 50, maxDelay = 2000, timeout = 1000)
	@Override
	public void reserve(String reservationId, List<OrderDraft.Line> lines) {
		ReservationRequest request = new ReservationRequest(
				reservationId,
				lines.stream().map(line -> new ReservationRequest.Line(line.sku(), line.quantity())).toList());
		try {
			this.client.reserve(request);
		}
		catch (InventoryException.Conflict ex) {
			throw new InsufficientStockException();
		}
		catch (InventoryException ex) {
			throw new InventoryUnavailableException("Inventory rejected the reservation with " + ex.getStatus(), ex);
		}
		catch (FilteredHostException ex) {
			// Blocked by an InetAddressFilter before any connection was made. Depending on the
			// underlying HTTP client this arrives bare, not wrapped in a RestClientException.
			throw new InventoryUnavailableException("Inventory address is blocked by the outbound filter", ex);
		}
		catch (RestClientException ex) {
			// Timeouts, refused connections and unreadable responses.
			throw new InventoryUnavailableException("Inventory could not be reached", ex);
		}
	}

	/** Same retry policy as {@link #reserve}. A 404 means nothing is reserved, which counts as released. */
	@Retryable(includes = InventoryUnavailableException.class, maxRetries = 3, delay = 200, multiplier = 2,
			jitter = 50, maxDelay = 2000)
	@Override
	public void release(String reservationId) {
		try {
			this.client.release(reservationId);
		}
		catch (InventoryException.NotFound ex) {
			// Nothing reserved for this order (or already released): the goal is met.
		}
		catch (InventoryException ex) {
			throw new InventoryUnavailableException("Inventory rejected the release with " + ex.getStatus(), ex);
		}
		catch (FilteredHostException ex) {
			throw new InventoryUnavailableException("Inventory address is blocked by the outbound filter", ex);
		}
		catch (RestClientException ex) {
			throw new InventoryUnavailableException("Inventory could not be reached", ex);
		}
	}

}

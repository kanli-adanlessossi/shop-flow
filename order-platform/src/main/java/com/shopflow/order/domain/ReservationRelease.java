package com.shopflow.order.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Gives back stock after order placement failed, without making the customer wait for it. Runs
 * on the {@code applicationTaskExecutor}; the trace context is copied over (see
 * {@code spring.task.execution.propagate-context}).
 *
 * <p>This is best effort. If inventory cannot be reached, the reservation stays held until
 * inventory-service expires it, so that service should give reservations a time limit.
 */
@Component
public class ReservationRelease {

	private static final Logger LOGGER = LoggerFactory.getLogger(ReservationRelease.class);

	private final StockReservations stock;

	public ReservationRelease(StockReservations stock) {
		this.stock = stock;
	}

	@Async
	public void releaseInBackground(String reservationId) {
		try {
			this.stock.release(reservationId);
		}
		catch (RuntimeException ex) {
			LOGGER.warn("Could not release reservation {}; it stays held until inventory expires it", reservationId,
					ex);
		}
	}

}

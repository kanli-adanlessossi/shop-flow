package com.shopflow.inventory.events;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shopflow.inventory.domain.StockService;

/**
 * Handles one order event exactly once in effect, although Kafka may deliver it more than once.
 *
 * <p>In one transaction it records the event id in the inbox ({@link ProcessedEvents}) and then moves the
 * reservation: {@code order.confirmed} to CONFIRMED, {@code order.shipped} to SHIPPED. A repeat is
 * skipped. A failure rolls back the inbox row too, so the retry is handled again.
 *
 * <p>Nothing here throws for data that retrying cannot fix: an unknown event type, an event without a
 * reservation id and an unknown reservation are logged and skipped, so they never reach the
 * dead-letter topic. The status only moves forward (see {@link StockService}), so events that arrive
 * out of order leave a SHIPPED reservation SHIPPED.
 */
@Service
public class OrderEventHandler {

	/** The Kafka group id of the listener; the inbox keeps one set of ids per consumer. */
	static final String CONSUMER = "inventory";

	private static final Logger LOGGER = LoggerFactory.getLogger(OrderEventHandler.class);

	private final ProcessedEvents processed;

	private final StockService stock;

	public OrderEventHandler(ProcessedEvents processed, StockService stock) {
		this.processed = processed;
		this.stock = stock;
	}

	@Transactional
	public void handle(EventEnvelope event) {
		switch (event.payload()) {
			case UnknownPayload unknown -> LOGGER.warn("Skipping event {} of a type this version does not know (fields {})",
					event.eventId(), unknown.fields().keySet());
			case OrderConfirmed confirmed -> apply(event, confirmed.reservationId(), true);
			case OrderShipped shipped -> apply(event, shipped.reservationId(), false);
		}
	}

	private void apply(EventEnvelope event, @Nullable String reservationId, boolean confirm) {
		if (reservationId == null || reservationId.isBlank()) {
			// Nothing was done, so nothing is recorded in the inbox.
			LOGGER.warn("Skipping event {} ({}): it carries no reservation id", event.eventId(), event.type());
			return;
		}
		if (!this.processed.markProcessed(CONSUMER, event.eventId())) {
			LOGGER.info("Ignoring duplicate delivery of event {} ({})", event.eventId(), event.type());
			return;
		}
		boolean changed = confirm ? this.stock.confirm(reservationId) : this.stock.ship(reservationId);
		LOGGER.info("Event {} ({}) for reservation {}: {}", event.eventId(), event.type(), reservationId,
				changed ? "status moved" : "no change");
	}

}

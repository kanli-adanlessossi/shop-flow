package com.shopflow.order.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles one order event exactly once in effect, although Kafka may deliver it more than once.
 *
 * <p>In one transaction it records the event id in the inbox ({@link ProcessedEvents}) and then
 * does the work. A repeat is skipped. A failure rolls back the inbox row too, so the retry or
 * redelivery is handled again; nothing is lost. The work itself ({@link NotificationSender}) is
 * outside the database, so it has the same at-least-once limit as any external call and gets the
 * event id as its idempotency key.
 */
@Service
public class OrderEventHandler {

	/** The Kafka group id of the listener; the inbox keeps one set of ids per consumer. */
	static final String CONSUMER = "notifications";

	private static final Logger LOGGER = LoggerFactory.getLogger(OrderEventHandler.class);

	private final ProcessedEvents processed;

	private final NotificationSender sender;

	public OrderEventHandler(ProcessedEvents processed, NotificationSender sender) {
		this.processed = processed;
		this.sender = sender;
	}

	@Transactional
	public void handle(EventEnvelope event) {
		if (event.payload() instanceof UnknownPayload unknown) {
			// Nothing was done, so nothing is recorded: a version that knows the type may handle it.
			LOGGER.warn("Skipping event {} of a type this version does not know (fields {})", event.eventId(),
					unknown.fields().keySet());
			return;
		}
		if (!this.processed.markProcessed(CONSUMER, event.eventId())) {
			LOGGER.info("Ignoring duplicate delivery of event {} ({})", event.eventId(), event.type());
			return;
		}
		this.sender.send(event.eventId(), event.payload());
	}

}

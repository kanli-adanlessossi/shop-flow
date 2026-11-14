package com.shopflow.order.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Stand-in until a real channel exists: the notice is only logged. */
@Component
class LoggingNotificationSender implements NotificationSender {

	private static final Logger LOGGER = LoggerFactory.getLogger(LoggingNotificationSender.class);

	@Override
	public void send(String idempotencyKey, Payload payload) {
		switch (payload) {
			case OrderPlaced placed -> LOGGER.info("Notice to customer {}: order {} placed, total {} (key {})",
					placed.customerId(), placed.orderId(), placed.total(), idempotencyKey);
			case OrderConfirmed confirmed -> LOGGER.info("Notice to customer {}: order {} confirmed, paid {} (key {})",
					confirmed.customerId(), confirmed.orderId(), confirmed.total(), idempotencyKey);
			case OrderCancelled cancelled -> LOGGER.info("Notice to customer {}: order {} cancelled, reason {} (key {})",
					cancelled.customerId(), cancelled.orderId(), cancelled.reason(), idempotencyKey);
			case OrderShipped shipped -> throw new IllegalArgumentException("Customers are not notified of shipping");
			case UnknownPayload unknown -> throw new IllegalArgumentException("Nothing to send for an unknown event");
		}
	}

}

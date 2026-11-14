package com.shopflow.order.events;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderEventHandlerTest {

	private final ProcessedEvents processed = mock(ProcessedEvents.class);

	private final NotificationSender sender = mock(NotificationSender.class);

	private final OrderEventHandler handler = new OrderEventHandler(this.processed, this.sender);

	@Test
	@DisplayName("order.shipped is for inventory: no inbox row and no notification")
	void shippedIsSkipped() {
		this.handler.handle(EventEnvelope.of(new OrderShipped(7, "res-7")));
		verifyNoInteractions(this.processed, this.sender);
	}
}

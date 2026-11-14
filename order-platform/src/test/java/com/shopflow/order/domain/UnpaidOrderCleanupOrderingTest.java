package com.shopflow.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.shopflow.order.ShopFlowProperties;

/**
 * The job must cancel an order before it releases the stock. Released first, a payment arriving between
 * the two steps would confirm an order that no longer has a reservation. Ordering is checked with mocks
 * because the window is a few milliseconds wide and cannot be hit reliably through the real services.
 */
class UnpaidOrderCleanupOrderingTest {

	private final OrderService orders = mock(OrderService.class);

	private final StockReservations stock = mock(StockReservations.class);

	private final ShopFlowProperties properties = mock(ShopFlowProperties.class);

	private final UnpaidOrderCleanup cleanup = new UnpaidOrderCleanup(this.orders, this.stock, this.properties,
			Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC));

	UnpaidOrderCleanupOrderingTest() {
		when(this.properties.cancelAfter()).thenReturn(Duration.ofMinutes(30));
	}

	@Test
	void theOrderIsCancelledBeforeItsStockIsReleased() {
		when(this.orders.findStockReleasePending(anyInt())).thenReturn(List.of());
		when(this.orders.findUnpaidCreatedBefore(any(), anyInt())).thenReturn(List.of(7L));
		when(this.orders.cancelIfStillUnpaid(anyLong(), any())).thenReturn(Optional.of("res-7"));

		this.cleanup.cancelUnpaidOrders();

		InOrder order = inOrder(this.orders, this.stock);
		order.verify(this.orders).cancelIfStillUnpaid(anyLong(), any());
		order.verify(this.stock).release("res-7");
		order.verify(this.orders).stockReleased(7L);
	}

	@Test
	void anOrderThatWasPaidInTheMeantimeIsNotReleased() {
		when(this.orders.findStockReleasePending(anyInt())).thenReturn(List.of());
		when(this.orders.findUnpaidCreatedBefore(any(), anyInt())).thenReturn(List.of(7L));
		when(this.orders.cancelIfStillUnpaid(anyLong(), any())).thenReturn(Optional.empty());

		this.cleanup.cancelUnpaidOrders();

		verify(this.stock, never()).release(any());
	}

	@Test
	void anOrderWhoseReleaseFailsWithAnOtherErrorIsStillCountedAsCancelled() {
		when(this.orders.findStockReleasePending(anyInt())).thenReturn(List.of());
		when(this.orders.findUnpaidCreatedBefore(any(), anyInt())).thenReturn(List.of(7L, 8L));
		when(this.orders.cancelIfStillUnpaid(anyLong(), any())).thenReturn(Optional.of("res-7"), Optional.of("res-8"));
		doThrow(new IllegalStateException("bad response")).when(this.stock).release("res-7");
		Logger logger = (Logger) LoggerFactory.getLogger(UnpaidOrderCleanup.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			this.cleanup.cancelUnpaidOrders();
		}
		finally {
			logger.detachAppender(appender);
		}

		// The run goes on with the next order, which is released normally.
		verify(this.stock).release("res-8");
		verify(this.orders).stockReleased(8L);
		verify(this.orders, never()).stockReleased(7L);
		assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
				.anyMatch(m -> m.startsWith("Unpaid-order cleanup: 2 cancelled, 0 skipped"));
	}

	@Test
	void aFailedReleaseIsRetriedByTheNextRunAndStaysFlaggedUntilThen() {
		when(this.orders.findStockReleasePending(anyInt())).thenReturn(List.of());
		when(this.orders.findUnpaidCreatedBefore(any(), anyInt())).thenReturn(List.of(7L));
		when(this.orders.cancelIfStillUnpaid(anyLong(), any())).thenReturn(Optional.of("res-7"));
		doThrow(new InventoryUnavailableException("down", new RuntimeException("timeout"))).when(this.stock).release("res-7");

		this.cleanup.cancelUnpaidOrders();
		verify(this.orders, never()).stockReleased(anyLong());

		// Next run, inventory is back: the pending release is done first.
		org.mockito.Mockito.reset(this.stock, this.orders);
		when(this.orders.findStockReleasePending(anyInt())).thenReturn(List.of(7L));
		when(this.orders.pendingReservationId(7L)).thenReturn(Optional.of("res-7"));
		when(this.orders.findUnpaidCreatedBefore(any(), anyInt())).thenReturn(List.of());

		this.cleanup.cancelUnpaidOrders();

		InOrder order = inOrder(this.stock, this.orders);
		order.verify(this.stock).release("res-7");
		order.verify(this.orders).stockReleased(7L);
	}
}

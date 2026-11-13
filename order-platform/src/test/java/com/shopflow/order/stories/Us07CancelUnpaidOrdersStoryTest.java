package com.shopflow.order.stories;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.shopflow.order.domain.UnpaidOrderCleanup;
import com.shopflow.order.events.OrderCancelled;

/**
 * The job runs on a schedule in production ({@code shopflow.cancel-cron}); here the schedule is off
 * and the test starts the real job bean itself, after moving its order's creation time back past
 * {@code shopflow.cancel-after} (30 minutes). Everything the job does is real: the queries, the
 * release call to inventory, the cancel, the event.
 */
@DisplayName("US-7 Cancel unpaid orders automatically")
class Us07CancelUnpaidOrdersStoryTest extends StoryTestSupport {

	@Autowired
	UnpaidOrderCleanup cleanup;

	@Test
	@DisplayName("an order unpaid for too long is cancelled, its stock released, the customer told")
	void unpaidTooLong() {
		long id = idOf(placeAlicesOrder());
		String reservationId = reservationIdOf(id);
		createdHoursAgo(id, 2);

		this.cleanup.cancelUnpaidOrders();

		assertThat(statusOf(id)).isEqualTo("CANCELLED");
		assertThat(this.inventory.releaseRequests(reservationId)).isEqualTo(1);
		assertThat(awaitNotice(OrderCancelled.class, id).reason()).isEqualTo("unpaid");
	}

	@Test
	@DisplayName("an order that is still within the time limit is left alone")
	void notYetDue() {
		long id = idOf(placeAlicesOrder());

		this.cleanup.cancelUnpaidOrders();

		assertThat(statusOf(id)).isEqualTo("PENDING_PAYMENT");
		assertThat(this.inventory.releaseRequests(reservationIdOf(id))).isZero();
	}

	@Test
	@DisplayName("a paid order is left alone, however old")
	void paidOrdersStay() {
		long id = idOf(placeAlicesOrder());
		pay(id, new BigDecimal("24.25")).expectStatus().isOk();
		createdHoursAgo(id, 2);

		this.cleanup.cancelUnpaidOrders();

		assertThat(statusOf(id)).isEqualTo("CONFIRMED");
		assertThat(this.inventory.releaseRequests(reservationIdOf(id))).isZero();
	}

	@Test
	@DisplayName("inventory down: the order stays unpaid and the next run cancels it")
	void inventoryDownThenBack() {
		long id = idOf(placeAlicesOrder());
		createdHoursAgo(id, 2);
		this.inventory.answerReleasesWith(503);

		this.cleanup.cancelUnpaidOrders();
		assertThat(statusOf(id)).isEqualTo("PENDING_PAYMENT");

		this.inventory.reset();
		this.cleanup.cancelUnpaidOrders();
		assertThat(statusOf(id)).isEqualTo("CANCELLED");
	}

	/** There is no API for this: an order's creation time never changes. */
	private void createdHoursAgo(long orderId, int hours) {
		this.jdbc.sql("update orders set created_at = :at where id = :id")
				.param("at", OffsetDateTime.now(ZoneOffset.UTC).minusHours(hours))
				.param("id", orderId)
				.update();
	}

}

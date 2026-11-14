package com.shopflow.order.stories;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.shopflow.order.events.OrderCancelled;
import com.shopflow.order.events.OrderConfirmed;

@DisplayName("US-9 Cancel an order")
class Us09CancelOrderStoryTest extends StoryTestSupport {

	@Test
	@DisplayName("an admin cancels an order; the customer is told once, even if it is cancelled again")
	void adminCancels() {
		long id = idOf(placeAlicesOrder());

		patchStatus(bob(), id, "CANCELLED").expectStatus().isOk()
				.expectBody().jsonPath("$.status").isEqualTo("CANCELLED");
		patchStatus(bob(), id, "CANCELLED").expectStatus().isOk();

		assertThat(awaitNotice(OrderCancelled.class, id).reason()).isEqualTo("cancelled on request");
		// Give a second (wrong) event time to arrive before checking there is only one.
		Awaitility.await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
				.until(() -> noticesFor(id).stream().filter(OrderCancelled.class::isInstance).count() == 1);
		// The owner sees it straight away (the cached copy was dropped).
		get(alice(), "/api/orders/{id}", id).expectStatus().isOk()
				.expectBody().jsonPath("$.status").isEqualTo("CANCELLED");
	}

	@Test
	@DisplayName("a customer cannot cancel, not even her own order")
	void customerCannot() {
		long id = idOf(placeAlicesOrder());

		patchStatus(alice(), id, "CANCELLED").expectStatus().isForbidden();

		assertThat(statusOf(id)).isEqualTo("PENDING_PAYMENT");
	}

	@Test
	@DisplayName("a service with the admin role may cancel")
	void serviceMayCancel() {
		long id = idOf(placeAlicesOrder());

		patchStatus(service(), id, "CANCELLED").expectStatus().isOk();
	}

	@Test
	@DisplayName("an admin's cancel gives the stock back, once, even if repeated")
	void cancelReleasesTheReservation() {
		long id = idOf(placeAlicesOrder());
		String reservationId = reservationIdOf(id);

		patchStatus(bob(), id, "CANCELLED").expectStatus().isOk();
		patchStatus(bob(), id, "CANCELLED").expectStatus().isOk();

		Awaitility.await().atMost(Duration.ofSeconds(15))
				.untilAsserted(() -> assertThat(this.inventory.releaseRequests(reservationId)).isEqualTo(1));
		// Give a second (wrong) release time to arrive.
		Awaitility.await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
				.until(() -> this.inventory.releaseRequests(reservationId) == 1);
	}

	@Test
	@DisplayName("a status that is not a legal next step is 409 and changes nothing")
	void illegalTransitions() {
		long id = idOf(placeAlicesOrder());

		// Skipping the payment is the point: neither SHIPPED nor CONFIRMED may be set by hand.
		patchStatus(bob(), id, "SHIPPED").expectStatus().isEqualTo(409)
				.expectBody().jsonPath("$.orderStatus").isEqualTo("PENDING_PAYMENT");
		patchStatus(bob(), id, "CONFIRMED").expectStatus().isEqualTo(409);
		assertThat(statusOf(id)).isEqualTo("PENDING_PAYMENT");
		assertThat(noticesFor(id).stream().filter(OrderConfirmed.class::isInstance)).isEmpty();

		// A cancelled order cannot be revived.
		patchStatus(bob(), id, "CANCELLED").expectStatus().isOk();
		patchStatus(bob(), id, "PENDING_PAYMENT").expectStatus().isEqualTo(409);
		patchStatus(bob(), id, "CONFIRMED").expectStatus().isEqualTo(409);
		assertThat(statusOf(id)).isEqualTo("CANCELLED");
	}

	@Test
	@DisplayName("a paid order cannot be cancelled, but an admin can ship it")
	void confirmedOrders() {
		long id = idOf(placeAlicesOrder());
		pay(id, new BigDecimal("24.25")).expectStatus().isOk();

		patchStatus(bob(), id, "CANCELLED").expectStatus().isEqualTo(409);
		assertThat(statusOf(id)).isEqualTo("CONFIRMED");

		patchStatus(bob(), id, "SHIPPED").expectStatus().isOk()
				.expectBody().jsonPath("$.status").isEqualTo("SHIPPED");
	}

	@Test
	@DisplayName("an owner cannot set any status on her own order (403), so she cannot skip the payment")
	void ownerCannotSetStatus() {
		long id = idOf(placeAlicesOrder());

		patchStatus(alice(), id, "SHIPPED").expectStatus().isForbidden();
		patchStatus(alice(), id, "CONFIRMED").expectStatus().isForbidden();

		assertThat(statusOf(id)).isEqualTo("PENDING_PAYMENT");
	}

}

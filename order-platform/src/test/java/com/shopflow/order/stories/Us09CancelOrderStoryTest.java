package com.shopflow.order.stories;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.shopflow.order.events.OrderCancelled;

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

}

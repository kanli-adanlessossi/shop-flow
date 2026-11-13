package com.shopflow.order.stories;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("US-4 Change or delete an unpaid order")
class Us04ChangeUnpaidOrderStoryTest extends StoryTestSupport {

	@Test
	@DisplayName("an unpaid order can be replaced, and reads back with the new content")
	void replaceWhileUnpaid() {
		long id = idOf(placeAlicesOrder());

		put(alice(), "/api/orders/{id}", orderJson("alice", "C:4:0.50"), id)
				.expectStatus().isOk()
				.expectBody().jsonPath("$.totals.amount").isEqualTo(2.00);

		get(alice(), "/api/orders/{id}", id)
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.lines.length()").isEqualTo(1)
				.jsonPath("$.lines[0].sku").isEqualTo("C");
	}

	@Test
	@DisplayName("a paid order can be neither replaced nor deleted (409 with its status)")
	void notOncePaid() {
		long id = idOf(placeAlicesOrder());
		pay(id, new BigDecimal("24.25")).expectStatus().isOk();

		put(alice(), "/api/orders/{id}", orderJson("alice", "C:1:1.00"), id)
				.expectStatus().isEqualTo(409)
				.expectBody().jsonPath("$.orderStatus").isEqualTo("CONFIRMED");
		delete(alice(), "/api/orders/{id}", id).expectStatus().isEqualTo(409);
	}

	@Test
	@DisplayName("an unpaid order can be deleted, and is gone afterwards")
	void deleteWhileUnpaid() {
		long id = idOf(placeAlicesOrder());

		delete(alice(), "/api/orders/{id}", id).expectStatus().isNoContent();
		get(alice(), "/api/orders/{id}", id).expectStatus().isNotFound();
	}

	@Test
	@DisplayName("a cancelled order can be deleted")
	void deleteWhenCancelled() {
		long id = idOf(placeAlicesOrder());
		patchStatus(bob(), id, "CANCELLED").expectStatus().isOk();

		delete(alice(), "/api/orders/{id}", id).expectStatus().isNoContent();
	}

	@Test
	@DisplayName("an order cannot be handed to another customer (403)")
	void notToSomeoneElse() {
		long id = idOf(placeAlicesOrder());

		put(alice(), "/api/orders/{id}", orderJson("bob", "C:1:1.00"), id).expectStatus().isForbidden();
	}

}

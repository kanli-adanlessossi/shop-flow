package com.shopflow.order.stories;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("US-4 Change or delete an unpaid order")
class Us04ChangeUnpaidOrderStoryTest extends StoryTestSupport {

	@Test
	@DisplayName("an unpaid order can be replaced, and reads back with the new content")
	void replaceWhileUnpaid() {
		long id = idOf(placeAlicesOrder());

		put(alice(), "/api/orders/{id}", orderJson("alice", "C:4"), id)
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

		put(alice(), "/api/orders/{id}", orderJson("alice", "C:1"), id)
				.expectStatus().isEqualTo(409)
				.expectBody().jsonPath("$.orderStatus").isEqualTo("CONFIRMED");
		delete(alice(), "/api/orders/{id}", id).expectStatus().isEqualTo(409);
	}

	@Test
	@DisplayName("an unpaid order can be deleted, and is gone afterwards")
	void deleteWhileUnpaid() {
		long id = idOf(placeAlicesOrder());

		String reservationId = reservationIdOf(id);

		delete(alice(), "/api/orders/{id}", id).expectStatus().isNoContent();
		get(alice(), "/api/orders/{id}", id).expectStatus().isNotFound();

		// The stock held for the deleted order is given back.
		Awaitility.await().atMost(Duration.ofSeconds(15))
				.untilAsserted(() -> assertThat(this.inventory.releaseRequests(reservationId)).isEqualTo(1));
	}

	@Test
	@DisplayName("a cancelled order can be deleted, and the delete gives back any stock still held")
	void deleteWhenCancelled() {
		long id = idOf(placeAlicesOrder());
		String reservationId = reservationIdOf(id);
		patchStatus(bob(), id, "CANCELLED").expectStatus().isOk();
		Awaitility.await().atMost(Duration.ofSeconds(15))
				.untilAsserted(() -> assertThat(this.inventory.releaseRequests(reservationId)).isEqualTo(1));

		delete(alice(), "/api/orders/{id}", id).expectStatus().isNoContent();

		// Releasing twice is harmless at inventory, and heals a reservation that an earlier release missed.
		Awaitility.await().atMost(Duration.ofSeconds(15))
				.untilAsserted(() -> assertThat(this.inventory.releaseRequests(reservationId)).isEqualTo(2));
	}

	@Test
	@DisplayName("a delete that is refused does not give the stock back")
	void refusedDeleteKeepsTheReservation() {
		long id = idOf(placeAlicesOrder());
		String reservationId = reservationIdOf(id);
		pay(id, new BigDecimal("24.25")).expectStatus().isOk();

		delete(alice(), "/api/orders/{id}", id).expectStatus().isEqualTo(409);

		Awaitility.await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
				.until(() -> this.inventory.releaseRequests(reservationId) == 0);
	}

	@Test
	@DisplayName("a replaced order is priced again by the server, whatever price the body carries")
	void replaceIsRepriced() {
		long id = idOf(placeAlicesOrder());
		String cheating = "{ \"customerId\": \"alice\", \"lines\": [ { \"sku\": \"A\", \"quantity\": 1, \"unitPrice\": 0.01 } ] }";

		put(alice(), "/api/orders/{id}", cheating, id)
				.expectStatus().isOk()
				.expectBody().jsonPath("$.totals.amount").isEqualTo(10.50);
	}

	@Test
	@DisplayName("replacing the lines moves the reservation: the new lines are reserved, the old ones released")
	void replaceMovesTheReservation() {
		long id = idOf(placeAlicesOrder());
		String before = reservationIdOf(id);

		put(alice(), "/api/orders/{id}", orderJson("alice", "C:4"), id).expectStatus().isOk();

		String after = reservationIdOf(id);
		assertThat(after).isNotEqualTo(before);
		assertThat(this.inventory.reservationRequests(after)).isEqualTo(1);
		Awaitility.await().atMost(Duration.ofSeconds(15))
				.untilAsserted(() -> assertThat(this.inventory.releaseRequests(before)).isEqualTo(1));
		assertThat(this.inventory.releaseRequests(after)).isZero();
	}

	@Test
	@DisplayName("replacing with the same SKUs and quantities does not touch inventory")
	void replaceWithSameLinesKeepsTheReservation() {
		long id = idOf(placeAlicesOrder());
		String before = reservationIdOf(id);

		put(alice(), "/api/orders/{id}", orderJson("alice", "B:1", "A:2"), id).expectStatus().isOk();

		assertThat(reservationIdOf(id)).isEqualTo(before);
		assertThat(this.inventory.releaseRequests(before)).isZero();
	}

	@Test
	@DisplayName("a replace that inventory cannot cover is refused (409) and the order keeps its lines and its reservation")
	void replaceRefusedForStock() {
		long id = idOf(placeAlicesOrder());
		String before = reservationIdOf(id);
		this.inventory.answerReservationsWith(409);

		put(alice(), "/api/orders/{id}", orderJson("alice", "C:1000"), id).expectStatus().isEqualTo(409);

		assertThat(reservationIdOf(id)).isEqualTo(before);
		assertThat(this.inventory.releaseRequests(before)).isZero();
		get(alice(), "/api/orders/{id}", id)
				.expectStatus().isOk()
				.expectBody().jsonPath("$.lines.length()").isEqualTo(2);
	}

	@Test
	@DisplayName("an order cannot be handed to another customer (403)")
	void notToSomeoneElse() {
		long id = idOf(placeAlicesOrder());

		put(alice(), "/api/orders/{id}", orderJson("bob", "C:1"), id).expectStatus().isForbidden();
	}

}

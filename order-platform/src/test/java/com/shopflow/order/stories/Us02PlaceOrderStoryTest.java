package com.shopflow.order.stories;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.shopflow.order.api.OrderV2;
import com.shopflow.order.events.OrderPlaced;

@DisplayName("US-2 Place an order")
class Us02PlaceOrderStoryTest extends StoryTestSupport {

	@Test
	@DisplayName("the order is created waiting for payment, with totals and a Location")
	void anOrderIsCreated() {
		var result = post(alice(), "/api/orders", orderJson("alice", "A:2:10.50", "B:1:3.25"))
				.expectStatus().isCreated()
				.expectHeader().exists(HttpHeaders.LOCATION)
				.expectBody(OrderV2.class).returnResult();
		OrderV2 order = result.getResponseBody();

		assertThat(order).isNotNull();
		assertThat(order.status().name()).isEqualTo("PENDING_PAYMENT");
		assertThat(order.lines()).extracting(OrderV2.Line::lineTotal)
				.usingElementComparator(java.math.BigDecimal::compareTo)
				.containsExactly(new java.math.BigDecimal("21.00"), new java.math.BigDecimal("3.25"));
		assertThat(order.totals().amount()).isEqualByComparingTo("24.25");
		assertThat(result.getResponseHeaders().getLocation()).hasPath("/api/orders/" + order.id());
	}

	@Test
	@DisplayName("the stock is reserved at inventory under the order's reservation id")
	void theStockIsReserved() {
		long id = idOf(placeAlicesOrder());

		String reservationId = reservationIdOf(id);
		assertThat(reservationId).isNotBlank();
		assertThat(this.inventory.reservationRequests(reservationId)).isEqualTo(1);
	}

	@Test
	@DisplayName("an order.placed event tells the customer")
	void theCustomerIsTold() {
		long id = idOf(placeAlicesOrder());

		OrderPlaced notice = awaitNotice(OrderPlaced.class, id);
		assertThat(notice.customerId()).isEqualTo("alice");
		assertThat(notice.total()).isEqualByComparingTo("24.25");
	}

	@Test
	@DisplayName("not enough stock: 409 and no order")
	void notEnoughStock() {
		this.inventory.answerReservationsWith(409);
		long before = orderCount();

		post(alice(), "/api/orders", orderJson("alice", "A:1:1.00"))
				.expectStatus().isEqualTo(409)
				.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody().jsonPath("$.title").isEqualTo("Insufficient stock");

		assertThat(orderCount()).isEqualTo(before);
	}

	@Test
	@DisplayName("inventory unavailable: 503 after retrying, no order, the reservation is released")
	void inventoryUnavailable() {
		this.inventory.answerReservationsWith(503);
		long before = orderCount();

		post(alice(), "/api/orders", orderJson("alice", "A:1:1.00"))
				.expectStatus().isEqualTo(503)
				.expectBody().jsonPath("$.detail").isEqualTo("Stock could not be checked right now. Please try again later.");

		assertThat(orderCount()).isEqualTo(before);
		// Inventory may have reserved anyway before failing, so the app releases in the background.
		Awaitility.await().atMost(Duration.ofSeconds(15))
				.untilAsserted(() -> assertThat(this.inventory.releaseRequests()).isPositive());
	}

	@Test
	@DisplayName("invalid input: 400 with one entry per field")
	void invalidInput() {
		post(alice(), "/api/orders", orderJson("alice", "A:0:-1"))
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.errors[*].field").value((Object fields) -> assertThat(fields.toString())
						.contains("lines[0].quantity").contains("lines[0].unitPrice"));
	}

	private long orderCount() {
		return this.jdbc.sql("select count(*) from orders").query(Long.class).single();
	}

}

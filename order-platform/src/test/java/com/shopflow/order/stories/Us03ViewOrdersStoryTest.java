package com.shopflow.order.stories;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("US-3 View orders")
class Us03ViewOrdersStoryTest extends StoryTestSupport {

	@Test
	@DisplayName("a customer sees her order with its lines and totals")
	void oneOrder() {
		long id = idOf(placeAlicesOrder());

		get(alice(), "/api/orders/{id}", id)
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.customerId").isEqualTo("alice")
				.jsonPath("$.lines.length()").isEqualTo(2)
				.jsonPath("$.totals.amount").isEqualTo(24.25);
	}

	@Test
	@DisplayName("someone else's order is 403, a missing one 404")
	void notHersOrMissing() {
		long bobsOrder = idOf(placeOrder(bob(), orderJson("bob", "A:1")));

		get(alice(), "/api/orders/{id}", bobsOrder).expectStatus().isForbidden();
		get(alice(), "/api/orders/{id}", 999_999_999).expectStatus().isNotFound();
	}

	@Test
	@DisplayName("the list is newest first, paged, filterable and sortable")
	void theList() {
		long older = idOf(placeAlicesOrder());
		long newer = idOf(placeAlicesOrder());

		get(alice(), "/api/orders?size=2")
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.content[0].id").isEqualTo(newer)
				.jsonPath("$.content[1].id").isEqualTo(older)
				.jsonPath("$.page.size").isEqualTo(2)
				.jsonPath("$.page.totalElements").value(Integer.class, (total) -> assertThat(total).isGreaterThanOrEqualTo(2));

		get(alice(), "/api/orders?status=PENDING_PAYMENT&size=100")
				.expectStatus().isOk()
				.expectBody().jsonPath("$.content[*].status").value((Object statuses) ->
						assertThat((List<?>) statuses).isNotEmpty().allMatch("PENDING_PAYMENT"::equals));

		get(alice(), "/api/orders?sort=total,desc&size=100")
				.expectStatus().isOk()
				.expectBody().jsonPath("$.content[*].total").value((Object totals) -> {
					List<Double> values = ((List<?>) totals).stream().map((t) -> ((Number) t).doubleValue()).toList();
					assertThat(values).isSortedAccordingTo(java.util.Comparator.reverseOrder());
				});
	}

	@Test
	@DisplayName("an unknown sort property is 400")
	void unknownSort() {
		get(alice(), "/api/orders?sort=nope").expectStatus().isBadRequest();
	}

	@Test
	@DisplayName("a customer's list shows only her own orders; an admin's list shows everyone's")
	void onlyOwnOrdersInTheList() {
		long alices = idOf(placeAlicesOrder());
		long bobs = idOf(placeOrder(bob(), orderJson("bob", "A:1")));

		get(alice(), "/api/orders?size=100")
				.expectStatus().isOk()
				.expectBody().jsonPath("$.content[*].customerId").value((Object customers) ->
						assertThat((List<?>) customers).isNotEmpty().allMatch("alice"::equals));
		get(alice(), "/api/orders?status=PENDING_PAYMENT&size=1000")
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.content[*].customerId").value((Object customers) ->
						assertThat((List<?>) customers).isNotEmpty().allMatch("alice"::equals))
				.jsonPath("$.content[*].id").value((Object ids) ->
						assertThat((List<Object>) ids).contains((int) alices).doesNotContain((int) bobs));
		// The count follows the same filter, so a customer cannot learn how many orders others have.
		long alicesOrders = this.jdbc.sql("select count(*) from orders where customer_id = 'alice'")
				.query(Long.class).single();
		get(alice(), "/api/orders?size=1")
				.expectStatus().isOk()
				.expectBody().jsonPath("$.page.totalElements").isEqualTo(alicesOrders);

		get(bob(), "/api/orders?size=1000")
				.expectStatus().isOk()
				.expectBody().jsonPath("$.content[*].id").value((Object ids) ->
						assertThat((List<Object>) ids).contains((int) alices, (int) bobs));
	}

}

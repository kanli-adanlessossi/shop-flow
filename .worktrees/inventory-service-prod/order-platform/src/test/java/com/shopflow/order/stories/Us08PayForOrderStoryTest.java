package com.shopflow.order.stories;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.shopflow.order.events.OrderConfirmed;
import com.shopflow.order.events.OrderShipped;
import com.shopflow.order.payments.WebhookSignature;

@DisplayName("US-8 Pay for an order")
class Us08PayForOrderStoryTest extends StoryTestSupport {

	private static final BigDecimal TOTAL = new BigDecimal("24.25");

	@Test
	@DisplayName("a signed payment for the total confirms the order and tells the customer")
	void paymentConfirms() {
		long id = idOf(placeAlicesOrder());

		pay(id, TOTAL).expectStatus().isOk().expectBody().jsonPath("$.result").isEqualTo("CONFIRMED");

		assertThat(statusOf(id)).isEqualTo("CONFIRMED");
		OrderConfirmed notice = awaitNotice(OrderConfirmed.class, id);
		assertThat(notice.total()).isEqualByComparingTo(TOTAL);
	}

	@Test
	@DisplayName("the same provider event again is DUPLICATE; another payment is ALREADY_CONFIRMED")
	void repeatedDeliveries() {
		long id = idOf(placeAlicesOrder());
		String eventId = "evt-repeat-" + id;

		paymentWebhook(eventId, "payment.succeeded", id, TOTAL).expectStatus().isOk()
				.expectBody().jsonPath("$.result").isEqualTo("CONFIRMED");
		paymentWebhook(eventId, "payment.succeeded", id, TOTAL).expectStatus().isOk()
				.expectBody().jsonPath("$.result").isEqualTo("DUPLICATE");
		pay(id, TOTAL).expectStatus().isOk()
				.expectBody().jsonPath("$.result").isEqualTo("ALREADY_CONFIRMED");

		awaitNotice(OrderConfirmed.class, id);
		assertThat(noticesFor(id)).filteredOn(OrderConfirmed.class::isInstance).hasSize(1);
	}

	@Test
	@DisplayName("order.confirmed carries the order's reservation id, for inventory")
	void confirmedEventHasReservationId() {
		long id = idOf(placeAlicesOrder());
		pay(id, TOTAL).expectStatus().isOk();
		assertThat(outboxPayloads(id, "order.confirmed")).singleElement()
				.satisfies((json) -> assertThat(json).contains("\"reservationId\":\"" + reservationIdOf(id) + "\""));
	}

	@Test
	@DisplayName("shipping a paid order writes one order.shipped event with the reservation id; again writes none")
	void shippingPublishesOrderShipped() {
		long id = idOf(placeAlicesOrder());
		pay(id, TOTAL).expectStatus().isOk();
		patchStatus(bob(), id, "SHIPPED").expectStatus().isOk();
		patchStatus(bob(), id, "SHIPPED").expectStatus().isOk();
		assertThat(statusOf(id)).isEqualTo("SHIPPED");
		assertThat(outboxPayloads(id, "order.shipped")).singleElement().satisfies((json) -> {
			assertThat(json).contains("\"orderId\":" + id);
			assertThat(json).contains("\"reservationId\":\"" + reservationIdOf(id) + "\"");
		});
		// Not for customers: no notice about it.
		assertThat(noticesFor(id)).noneMatch(OrderShipped.class::isInstance);
	}

	private List<String> outboxPayloads(long orderId, String type) {
		return this.jdbc.sql("select payload from outbox_events where message_key = :key "
				+ "and cast(payload as jsonb) ->> 'type' = :type")
				.param("key", String.valueOf(orderId)).param("type", type).query(String.class).list();
	}

	@Test
	@DisplayName("a wrong amount is 422 and the order stays unpaid")
	void wrongAmount() {
		long id = idOf(placeAlicesOrder());

		pay(id, new BigDecimal("1.00")).expectStatus().isEqualTo(422);

		assertThat(statusOf(id)).isEqualTo("PENDING_PAYMENT");
	}

	@Test
	@DisplayName("a payment for a cancelled order is 409: it needs a refund")
	void lateForCancelledOrder() {
		long id = idOf(placeAlicesOrder());
		patchStatus(bob(), id, "CANCELLED").expectStatus().isOk();

		pay(id, TOTAL).expectStatus().isEqualTo(409)
				.expectBody().jsonPath("$.title").isEqualTo("Order already cancelled");
		assertThat(statusOf(id)).isEqualTo("CANCELLED");
	}

	@Test
	@DisplayName("payment.failed is acknowledged and ignored")
	void failedPaymentIsIgnored() {
		long id = idOf(placeAlicesOrder());

		paymentWebhook("evt-failed-" + id, "payment.failed", id, TOTAL).expectStatus().isOk()
				.expectBody().jsonPath("$.result").isEqualTo("IGNORED");
		assertThat(statusOf(id)).isEqualTo("PENDING_PAYMENT");
	}

	@Test
	@DisplayName("a missing or wrong signature is 401")
	void badSignature() {
		long id = idOf(placeAlicesOrder());
		byte[] body = ("{\"eventId\":\"evt-forged\",\"type\":\"payment.succeeded\",\"data\":{\"orderId\":" + id
				+ ",\"paymentId\":\"p\",\"amount\":24.25}}").getBytes(StandardCharsets.UTF_8);

		this.http.post().uri("/api/webhooks/payments").contentType(MediaType.APPLICATION_JSON).body(body).exchange()
				.expectStatus().isUnauthorized();
		this.http.post().uri("/api/webhooks/payments").contentType(MediaType.APPLICATION_JSON)
				.header(WebhookSignature.HEADER, WebhookSignature.sign("not-the-secret", Instant.now().getEpochSecond(), body))
				.body(body).exchange()
				.expectStatus().isUnauthorized();
		assertThat(statusOf(id)).isEqualTo("PENDING_PAYMENT");
	}

}

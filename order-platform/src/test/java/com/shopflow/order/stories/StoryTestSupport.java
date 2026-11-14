package com.shopflow.order.stories;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.mockito.Mockito;

import com.shopflow.order.InventoryStandInContainer;
import com.shopflow.order.InventoryTestcontainersConfiguration;
import com.shopflow.order.KeycloakTestcontainersConfiguration;
import com.shopflow.order.TestcontainersConfiguration;
import com.shopflow.order.api.OrderV2;
import com.shopflow.order.events.NotificationSender;
import com.shopflow.order.events.OrderCancelled;
import com.shopflow.order.events.OrderConfirmed;
import com.shopflow.order.events.OrderPlaced;
import com.shopflow.order.events.Payload;
import com.shopflow.order.payments.WebhookSignature;

import dasniko.testcontainers.keycloak.KeycloakContainer;

/**
 * Base of the user-story acceptance tests (docs/user-stories.md). Every story class extends it and
 * adds nothing to the context, so they all share ONE cached application context and one set of
 * containers:
 * <ul>
 * <li>the app on a random real port, called over HTTP with {@link RestTestClient};</li>
 * <li>PostgreSQL and Kafka ({@link TestcontainersConfiguration}): Flyway schema, outbox, relay,
 * notifications consumer, retry and dead-letter topics all for real;</li>
 * <li>Keycloak with the real {@code shopflow} realm ({@link KeycloakTestcontainersConfiguration});
 * tokens are issued by it and validated by the app;</li>
 * <li>the WireMock inventory stand-in ({@link InventoryTestcontainersConfiguration}).</li>
 * </ul>
 * The only test double is a spy on {@link NotificationSender}: the real (logging) sender still
 * runs, and the spy records what customers would be told.
 *
 * <p>Requests commit, nothing is rolled back: each test works with the orders it creates and asserts
 * on those (or on differences), never on totals across the database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"shopflow.payments.webhook-secret=" + StoryTestSupport.WEBHOOK_SECRET,
		// The unpaid-order job is started by the US-7 test itself, never by the clock mid-test.
		"shopflow.cancel-cron=-",
		"shopflow.outbox-relay-delay=200ms" })
@AutoConfigureRestTestClient
@Import({ TestcontainersConfiguration.class, KeycloakTestcontainersConfiguration.class,
		InventoryTestcontainersConfiguration.class })
@Testcontainers(disabledWithoutDocker = true)
@Tag("story")
abstract class StoryTestSupport {

	static final String WEBHOOK_SECRET = "story-test-webhook-secret";

	/** How long an event may take from the outbox to the notification sender (incl. retries). */
	static final Duration EVENT_TIMEOUT = Duration.ofSeconds(45);

	@Autowired
	RestTestClient http;

	@Autowired
	KeycloakContainer keycloak;

	@Autowired
	InventoryStandInContainer inventory;

	@Autowired
	JdbcClient jdbc;

	@MockitoSpyBean
	NotificationSender notifications;

	@AfterEach
	void restoreInventory() {
		this.inventory.reset();
	}

	// ---- signing in ---------------------------------------------------------------------

	String alice() {
		return this.keycloak.getAccessToken(KeycloakTestcontainersConfiguration.REALM,
				KeycloakTestcontainersConfiguration.WEB_CLIENT, "alice", "alice-pass");
	}

	String bob() {
		return this.keycloak.getAccessToken(KeycloakTestcontainersConfiguration.REALM,
				KeycloakTestcontainersConfiguration.WEB_CLIENT, "bob", "bob-pass");
	}

	String service() {
		return this.keycloak.getClientCredentialsToken(KeycloakTestcontainersConfiguration.REALM,
				KeycloakTestcontainersConfiguration.SERVICE_CLIENT, KeycloakTestcontainersConfiguration.SERVICE_SECRET);
	}

	// ---- calling the API (version 2 unless a test says otherwise) -------------------------

	RestTestClient.ResponseSpec get(String token, String uri, Object... vars) {
		return this.http.get().uri(uri, vars).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("X-API-Version", "2").exchange();
	}

	RestTestClient.ResponseSpec post(String token, String uri, String json, Object... vars) {
		return this.http.post().uri(uri, vars).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("X-API-Version", "2").contentType(MediaType.APPLICATION_JSON).body(json).exchange();
	}

	RestTestClient.ResponseSpec put(String token, String uri, String json, Object... vars) {
		return this.http.put().uri(uri, vars).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("X-API-Version", "2").contentType(MediaType.APPLICATION_JSON).body(json).exchange();
	}

	RestTestClient.ResponseSpec patchStatus(String token, long orderId, String status) {
		return this.http.patch().uri("/api/orders/{id}/status", orderId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("X-API-Version", "2")
				.contentType(MediaType.APPLICATION_JSON).body("{ \"status\": \"" + status + "\" }").exchange();
	}

	RestTestClient.ResponseSpec delete(String token, String uri, Object... vars) {
		return this.http.delete().uri(uri, vars).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
	}

	// ---- orders -------------------------------------------------------------------------

	/** Order body; each line is "SKU:quantity", e.g. "A:2". Prices come from the price list seeded by afterMigrate__test_sku_prices.sql (A 10.50, B 3.25, C 0.50, POISON 66.66). */
	static String orderJson(String customerId, String... lines) {
		StringBuilder json = new StringBuilder("{ \"customerId\": \"" + customerId + "\", \"lines\": [");
		for (int i = 0; i < lines.length; i++) {
			String[] parts = lines[i].split(":");
			json.append(i == 0 ? "" : ",").append("{ \"sku\": \"").append(parts[0]).append("\", \"quantity\": ")
					.append(parts[1]).append(" }");
		}
		return json.append("] }").toString();
	}

	/** Places an order as alice: 2 x A (10.50) + 1 x B (3.25) = 24.25. */
	OrderV2 placeAlicesOrder() {
		return placeOrder(alice(), orderJson("alice", "A:2", "B:1"));
	}

	OrderV2 placeOrder(String token, String json) {
		return Objects.requireNonNull(post(token, "/api/orders", json)
				.expectStatus().isCreated()
				.expectBody(OrderV2.class).returnResult().getResponseBody());
	}

	/** Status as an admin sees it through the API. */
	String statusOf(long orderId) {
		OrderV2 order = Objects.requireNonNull(get(bob(), "/api/orders/{id}", orderId)
				.expectStatus().isOk().expectBody(OrderV2.class).returnResult().getResponseBody());
		return order.status().name();
	}

	String reservationIdOf(long orderId) {
		return this.jdbc.sql("select reservation_id from orders where id = :id").param("id", orderId)
				.query(String.class).single();
	}

	// ---- the payment provider -----------------------------------------------------------

	/** Sends a signed webhook, as the payment provider would. */
	RestTestClient.ResponseSpec paymentWebhook(String eventId, String type, long orderId, BigDecimal amount) {
		byte[] body = """
				{"eventId":"%s","type":"%s","data":{"orderId":%d,"paymentId":"pay-%s","amount":%s}}
				""".formatted(eventId, type, orderId, eventId, amount.toPlainString()).getBytes(StandardCharsets.UTF_8);
		String signature = WebhookSignature.sign(WEBHOOK_SECRET, Instant.now().getEpochSecond(), body);
		return this.http.post().uri("/api/webhooks/payments").contentType(MediaType.APPLICATION_JSON)
				.header(WebhookSignature.HEADER, signature).body(body).exchange();
	}

	RestTestClient.ResponseSpec pay(long orderId, BigDecimal amount) {
		return paymentWebhook("evt-" + UUID.randomUUID(), "payment.succeeded", orderId, amount);
	}

	// ---- what the customer is told ------------------------------------------------------

	/** Every notification sent so far (in this test) about the order, in order. */
	List<Payload> noticesFor(long orderId) {
		return Mockito.mockingDetails(this.notifications).getInvocations().stream()
				.filter((call) -> call.getMethod().getName().equals("send"))
				.map((call) -> (Payload) call.getArgument(1))
				.filter((payload) -> orderIdOf(payload) == orderId)
				.toList();
	}

	/** Waits until the customer has been told about the order with this kind of notice. */
	<T extends Payload> T awaitNotice(Class<T> type, long orderId) {
		return Awaitility.await().atMost(EVENT_TIMEOUT).pollInterval(Duration.ofMillis(200))
				.until(() -> noticesFor(orderId).stream().filter(type::isInstance).map(type::cast).findFirst(),
						java.util.Optional::isPresent)
				.orElseThrow();
	}

	static long orderIdOf(Payload payload) {
		if (payload instanceof OrderPlaced placed) {
			return placed.orderId();
		}
		if (payload instanceof OrderConfirmed confirmed) {
			return confirmed.orderId();
		}
		if (payload instanceof OrderCancelled cancelled) {
			return cancelled.orderId();
		}
		return -1;
	}

	static long idOf(OrderV2 order) {
		return Objects.requireNonNull(order.id());
	}

}

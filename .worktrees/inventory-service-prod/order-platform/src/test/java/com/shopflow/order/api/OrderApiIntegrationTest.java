package com.shopflow.order.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.assertj.RestTestClientResponse;

import com.shopflow.order.domain.StockReservations;

/**
 * The orders API through the whole application: {@code RestTestClient} sends real HTTP-shaped
 * requests to the MockMvc instance that {@code @AutoConfigureMockMvc} builds over the full context
 * (security filter chain, controllers, {@code @PreAuthorize}, service, cache, JPA on H2, Flyway, the
 * outbox). No server port is opened.
 *
 * <p>Only the edges are replaced:
 * <ul>
 * <li>{@link JwtDecoder}: tokens are plain strings ({@code alice-token}, ...) decoded into
 * Keycloak-shaped JWTs. Everything after decoding is the app's own code, including the claim
 * mapping: the subject is an opaque id, so the caller's name must come from
 * {@code preferred_username}, and the roles from {@code realm_access.roles}.</li>
 * <li>{@link StockReservations}: no inventory-service.</li>
 * <li>Kafka: the listener does not start and no topics are created; events stay in the outbox
 * table, where the test reads them.</li>
 * </ul>
 * Requests are not rolled back, so each test works on the orders it creates.
 */
@SpringBootTest(properties = { "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
		// The relay's first run finds no rows; later rows wait for an hour instead of trying Kafka.
		"shopflow.outbox-relay-delay=1h" })
@AutoConfigureMockMvc
// Needed as well: @AutoConfigureMockMvc builds MockMvc but no RestTestClient bean (Boot 4.1.2).
// With a MockMvc bean and no running server, this binds the client to MockMvc.
@AutoConfigureRestTestClient
class OrderApiIntegrationTest {

	private static final String ALICE = "Bearer alice-token";

	private static final String BOB = "Bearer bob-token";

	private static final String NEW_ORDER = """
			{ "customerId": "alice",
			  "lines": [ { "sku": "A", "quantity": 2 },
			             { "sku": "B", "quantity": 1 } ] }
			""";

	@Autowired
	RestTestClient client;

	@Autowired
	JdbcClient jdbc;

	@MockitoBean
	JwtDecoder jwtDecoder;

	@MockitoBean
	StockReservations stock;

	@BeforeEach
	void tokens() {
		// Unknown tokens are rejected the way a real decoder rejects a bad signature.
		// willX().given(mock) form: given(mock.decode(..)) would call the already throwing stub.
		willThrow(new BadJwtException("invalid token")).given(this.jwtDecoder).decode(anyString());
		willReturn(TestUsers.token(UUID.randomUUID().toString(), "alice", "customer"))
				.given(this.jwtDecoder).decode("alice-token");
		willReturn(TestUsers.token(UUID.randomUUID().toString(), "bob", "admin"))
				.given(this.jwtDecoder).decode("bob-token");
	}

	@Test
	void aCustomerPlacesAnOrderAndReadsItBack() {
		OrderV2 created = Objects.requireNonNull(this.client.post().uri("/api/orders")
				.header(HttpHeaders.AUTHORIZATION, ALICE)
				.header("X-API-Version", "2")
				.contentType(MediaType.APPLICATION_JSON)
				.body(NEW_ORDER)
				.exchange()
				.expectStatus().isCreated()
				.expectHeader().exists(HttpHeaders.LOCATION)
				.expectBody(OrderV2.class)
				.returnResult()
				.getResponseBody());

		assertThat(created.status().name()).isEqualTo("PENDING_PAYMENT");
		assertThat(created.totals().amount()).isEqualByComparingTo("24.25");
		verify(this.stock).reserve(anyString(), anyList());

		this.client.get().uri(URI.create("/api/orders/" + created.id()))
				.header(HttpHeaders.AUTHORIZATION, ALICE)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.id").isEqualTo(created.id())
				.jsonPath("$.customerId").isEqualTo("alice")
				.jsonPath("$.lines.length()").isEqualTo(2);

		// The order and its event were committed together.
		assertThat(outboxTypes(created.id())).containsExactly("order.placed");
	}

	@Test
	void theCallerIsNamedAfterPreferredUsernameNotTheOpaqueSubject() {
		long alicesOrder = placeOrderAsAlice();

		// alice's token has a random UUID as subject: ownership only works if the name is "alice".
		this.client.get().uri("/api/orders/{id}", alicesOrder).header(HttpHeaders.AUTHORIZATION, ALICE)
				.exchange().expectStatus().isOk();
		this.client.post().uri("/api/orders").header(HttpHeaders.AUTHORIZATION, ALICE)
				.contentType(MediaType.APPLICATION_JSON).body(NEW_ORDER.replace("\"alice\"", "\"bob\""))
				.exchange().expectStatus().isForbidden();
	}

	@Test
	void onlyAnAdminMayCancelAndTheCancellationIsRecordedAsAnEvent() {
		long id = placeOrderAsAlice();

		// The same assertions in AssertJ style: RestTestClientResponse adapts a response spec.
		var refused = this.client.patch().uri("/api/orders/{id}/status", id)
				.header(HttpHeaders.AUTHORIZATION, ALICE).header("X-API-Version", "2")
				.contentType(MediaType.APPLICATION_JSON).body("{ \"status\": \"CANCELLED\" }")
				.exchange();
		assertThat(RestTestClientResponse.from(refused)).hasStatus(HttpStatus.FORBIDDEN);

		var cancelled = this.client.patch().uri("/api/orders/{id}/status", id)
				.header(HttpHeaders.AUTHORIZATION, BOB).header("X-API-Version", "2")
				.contentType(MediaType.APPLICATION_JSON).body("{ \"status\": \"CANCELLED\" }")
				.exchange();
		assertThat(RestTestClientResponse.from(cancelled)).hasStatusOk()
				.bodyJson().extractingPath("$.status").isEqualTo("CANCELLED");

		assertThat(outboxTypes(id)).containsExactly("order.placed", "order.cancelled");

		// The cached copy was evicted: the owner sees the new status straight away.
		this.client.get().uri("/api/orders/{id}", id).header(HttpHeaders.AUTHORIZATION, ALICE)
				.header("X-API-Version", "2").exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.status").isEqualTo("CANCELLED");
	}

	@Test
	void anAdminReadsTheSalesReportAndACustomerDoesNot() {
		placeOrderAsAlice();

		this.client.get().uri("/api/admin/reports/daily-sales").header(HttpHeaders.AUTHORIZATION, BOB)
				.exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.totals.orderCount").value(Integer.class,
						(count) -> assertThat(count).isPositive());
		this.client.get().uri("/api/admin/reports/daily-sales").header(HttpHeaders.AUTHORIZATION, ALICE)
				.exchange().expectStatus().isForbidden();
	}

	@Test
	void aBadOrMissingTokenIsRejectedWith401() {
		// Spring Security 7 points clients at the OAuth 2.0 Protected Resource Metadata (RFC 9728).
		this.client.get().uri("/api/orders").exchange()
				.expectStatus().isUnauthorized()
				.expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE,
						"Bearer resource_metadata=\"http://localhost/.well-known/oauth-protected-resource\"");
		this.client.get().uri("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer forged").exchange()
				.expectStatus().isUnauthorized()
				.expectHeader().value(HttpHeaders.WWW_AUTHENTICATE,
						(value) -> assertThat(value).startsWith("Bearer error=\"invalid_token\""));
	}

	@Test
	void theResourceMetadataNamedInThe401IsPublic() {
		// Served by Spring Security itself (RFC 9728), ahead of the app's "everything else needs a
		// token" rule. It lists no authorization server: nothing here tells clients about Keycloak.
		this.client.get().uri("/.well-known/oauth-protected-resource").exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.resource").isEqualTo("http://localhost")
				.jsonPath("$.bearer_methods_supported[0]").isEqualTo("header")
				.jsonPath("$.authorization_servers").doesNotExist();
	}

	@Test
	void validationErrorsComeBackAsAProblemDetail() {
		this.client.post().uri("/api/orders").header(HttpHeaders.AUTHORIZATION, ALICE)
				.contentType(MediaType.APPLICATION_JSON)
				.body("{ \"customerId\": \"alice\", \"lines\": [] }")
				.exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody().jsonPath("$.errors[0].field").isEqualTo("lines");
	}

	// ---- helpers ------------------------------------------------------------------------

	private long placeOrderAsAlice() {
		OrderV2 created = this.client.post().uri("/api/orders").header(HttpHeaders.AUTHORIZATION, ALICE)
				.contentType(MediaType.APPLICATION_JSON).body(NEW_ORDER)
				.exchange()
				.expectStatus().isCreated()
				.expectBody(OrderV2.class).returnResult().getResponseBody();
		return Objects.requireNonNull(created).id();
	}

	/** The types of the events recorded for an order, oldest first. */
	private List<String> outboxTypes(long orderId) {
		return this.jdbc.sql("select payload from outbox_events where message_key = :key order by id")
				.param("key", String.valueOf(orderId))
				.query(String.class)
				.list()
				.stream()
				.map((json) -> json.replaceAll("(?s).*\"type\":\"([^\"]+)\".*", "$1"))
				.toList();
	}

}

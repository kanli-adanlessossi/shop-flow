package com.shopflow.inventory.api;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import tools.jackson.databind.json.JsonMapper;

import com.shopflow.inventory.InventoryIntegrationTest;

/**
 * The whole service over HTTP with a real Keycloak, PostgreSQL and Flyway: who may call the stock
 * API, and the reservation round trip with a real service-account token. Shares the context of
 * {@link InventoryIntegrationTest}.
 */
class SecurityIntegrationTest extends InventoryIntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** An empty {@code json} sends no body. */
	private RestTestClient.ResponseSpec call(HttpMethod method, String token, String uri, String json) {
		return send(this.http.method(method).uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token), json);
	}

	private RestTestClient.ResponseSpec anonymous(HttpMethod method, String uri, String json) {
		return send(this.http.method(method).uri(uri), json);
	}

	private static RestTestClient.ResponseSpec send(RestTestClient.RequestBodySpec spec, String json) {
		if (json.isEmpty()) {
			return spec.exchange();
		}
		return spec.contentType(MediaType.APPLICATION_JSON).body(json).exchange();
	}

	private RestTestClient.ResponseSpec get(String token, String uri) {
		return call(HttpMethod.GET, token, uri, "");
	}

	private RestTestClient.ResponseSpec delete(String token, String uri) {
		return call(HttpMethod.DELETE, token, uri, "");
	}

	private RestTestClient.ResponseSpec post(String token, String uri, String json) {
		return call(HttpMethod.POST, token, uri, json);
	}

	private String newStock(int available) {
		String sku = "SEC-" + UUID.randomUUID();
		this.jdbc.sql("insert into stock (sku, available) values (?, ?)").params(sku, available).update();
		return sku;
	}

	private static String reserveJson(String reservationId, String sku, int quantity) {
		return "{\"reservationId\": \"" + reservationId + "\", \"lines\": [{\"sku\": \"" + sku + "\", \"quantity\": "
				+ quantity + "}]}";
	}

	@Test
	void theServiceAccountTokenCarriesTheInventoryRole() throws Exception {
		String payload = serviceToken().split("\\.")[1];
		Map<?, ?> claims = JSON.readValue(Base64.getUrlDecoder().decode(payload), Map.class);
		Map<?, ?> realmAccess = (Map<?, ?>) requireNonNull(claims.get("realm_access"));
		List<?> roles = (List<?>) requireNonNull(realmAccess.get("roles"));
		assertThat(roles.stream().map(Object::toString)).contains("inventory", "admin");
	}

	@Test
	void withoutATokenTheAnswerIs401() {
		anonymous(HttpMethod.GET, "/api/stock/SKU-001", "").expectStatus().isUnauthorized();
		anonymous(HttpMethod.POST, "/api/stock/reservations", reserveJson("x", "SKU-001", 1)).expectStatus()
				.isUnauthorized();
		anonymous(HttpMethod.DELETE, "/api/stock/reservations/x", "").expectStatus().isUnauthorized();
	}

	@Test
	void aUserWithoutTheInventoryRoleGets403() {
		String alice = aliceToken();
		get(alice, "/api/stock/SKU-001").expectStatus().isForbidden();
		post(alice, "/api/stock/reservations", reserveJson("x", "SKU-001", 1)).expectStatus().isForbidden();
		delete(alice, "/api/stock/reservations/x").expectStatus().isForbidden();
	}

	@Test
	void healthIsOpen() {
		anonymous(HttpMethod.GET, "/actuator/health", "").expectStatus().isOk();
	}

	@Test
	void theServiceAccountCanReserveReadAndReleaseStock() {
		String token = serviceToken();
		String sku = newStock(10);
		String id = "sec-" + UUID.randomUUID();

		post(token, "/api/stock/reservations", reserveJson(id, sku, 4)).expectStatus().isOk()
				.expectHeader().exists("X-Correlation-Id")
				.expectBody().jsonPath("$.reservationId").isEqualTo(id);
		// The same id again: still 200, stock taken once.
		post(token, "/api/stock/reservations", reserveJson(id, sku, 4)).expectStatus().isOk();
		get(token, "/api/stock/" + sku).expectStatus().isOk()
				.expectBody().jsonPath("$.available").isEqualTo(6).jsonPath("$.sku").isEqualTo(sku);

		delete(token, "/api/stock/reservations/" + id).expectStatus().isNoContent();
		get(token, "/api/stock/" + sku).expectStatus().isOk()
				.expectBody().jsonPath("$.available").isEqualTo(10);
		delete(token, "/api/stock/reservations/" + id).expectStatus().isNotFound();
	}

	@Test
	void anUncoveredLineIsA409ProblemAndChangesNothing() {
		String token = serviceToken();
		String sku = newStock(2);
		String id = "sec-" + UUID.randomUUID();
		post(token, "/api/stock/reservations", reserveJson(id, sku, 3)).expectStatus().isEqualTo(409)
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody().jsonPath("$.title").isEqualTo("Insufficient stock");
		get(token, "/api/stock/" + sku).expectStatus().isOk()
				.expectBody().jsonPath("$.available").isEqualTo(2);
	}

	@Test
	void releasingAShippedReservationIsA409() {
		String token = serviceToken();
		String sku = newStock(5);
		String id = "sec-" + UUID.randomUUID();
		post(token, "/api/stock/reservations", reserveJson(id, sku, 1)).expectStatus().isOk();
		this.jdbc.sql("update reservation set status = 'SHIPPED' where reservation_id = ?").params(id).update();
		delete(token, "/api/stock/reservations/" + id).expectStatus().isEqualTo(409);
		get(token, "/api/stock/" + sku).expectBody().jsonPath("$.available").isEqualTo(4);
	}

	@Test
	void anInvalidBodyIsA400Problem() {
		post(serviceToken(), "/api/stock/reservations", "{\"reservationId\": \"\", \"lines\": []}")
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
	}

	@Test
	void anUnknownSkuIsA404() {
		get(serviceToken(), "/api/stock/NO-SUCH-SKU").expectStatus().isNotFound();
	}

}

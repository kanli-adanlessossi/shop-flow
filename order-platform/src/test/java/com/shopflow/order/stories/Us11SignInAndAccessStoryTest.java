package com.shopflow.order.stories;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import com.shopflow.order.KeycloakTestcontainersConfiguration;

import dasniko.testcontainers.keycloak.KeycloakContainer;

/**
 * The acceptance view of the access rules. The detailed checks (tampered and foreign tokens, the
 * claim mapping, every endpoint without a token) live in KeycloakSecurityIntegrationTest and
 * AnonymousAccessTest.
 */
@DisplayName("US-11 Sign in and access rules")
class Us11SignInAndAccessStoryTest extends StoryTestSupport {

	@Test
	@DisplayName("users sign in with a password, services with client credentials")
	void signingIn() {
		get(alice(), "/api/orders").expectStatus().isOk();
		get(service(), "/api/orders").expectStatus().isOk();
		assertThatThrownBy(() -> this.keycloak.getAccessToken(KeycloakTestcontainersConfiguration.REALM,
				KeycloakTestcontainersConfiguration.WEB_CLIENT, "alice", "wrong-password"))
				.as("a wrong password gets no token").isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("customers work on their own orders; admins on everything")
	void rolesAndOwnership() {
		long alicesOrder = idOf(placeAlicesOrder());
		long bobsOrder = idOf(placeOrder(bob(), orderJson("bob", "A:1:1.00")));

		get(alice(), "/api/orders/{id}", bobsOrder).expectStatus().isForbidden();
		get(bob(), "/api/orders/{id}", alicesOrder).expectStatus().isOk();
		get(alice(), "/api/admin/reports/daily-sales").expectStatus().isForbidden();
		get(bob(), "/api/admin/reports/daily-sales").expectStatus().isOk();
	}

	@Test
	@DisplayName("only health is open without a token; the webhook needs its signature instead")
	void onlyHealthIsOpen() {
		this.http.get().uri("/actuator/health").exchange().expectStatus().isOk();
		this.http.get().uri("/api/orders").exchange().expectStatus().isUnauthorized();
		this.http.get().uri("/api/health-info").exchange().expectStatus().isUnauthorized();
		this.http.get().uri("/actuator/info").exchange().expectStatus().isUnauthorized();
		this.http.post().uri("/api/webhooks/payments").body("{}").header(HttpHeaders.CONTENT_TYPE, "application/json")
				.exchange().expectStatus().isUnauthorized();
		this.http.get().uri("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + masterRealmToken()).exchange()
				.expectStatus().isUnauthorized();
	}

	@Test
	@DisplayName("the web front end may call the API from a browser; other sites may not")
	void cors() {
		this.http.options().uri("/api/orders")
				.header(HttpHeaders.ORIGIN, "http://localhost:5173")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173");
		this.http.options().uri("/api/orders")
				.header(HttpHeaders.ORIGIN, "https://evil.example")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
				.exchange()
				.expectStatus().isForbidden();
	}

	/** Correctly signed by the same Keycloak, but for another realm. */
	private String masterRealmToken() {
		return this.keycloak.getAccessToken(KeycloakContainer.MASTER_REALM, KeycloakContainer.ADMIN_CLI_CLIENT,
				this.keycloak.getAdminUsername(), this.keycloak.getAdminPassword());
	}

}

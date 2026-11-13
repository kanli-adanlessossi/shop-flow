package com.shopflow.order.api;

import static com.shopflow.order.KeycloakTestcontainersConfiguration.REALM;
import static com.shopflow.order.KeycloakTestcontainersConfiguration.SERVICE_CLIENT;
import static com.shopflow.order.KeycloakTestcontainersConfiguration.SERVICE_SECRET;
import static com.shopflow.order.KeycloakTestcontainersConfiguration.WEB_CLIENT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.shopflow.order.KeycloakTestcontainersConfiguration;
import com.shopflow.order.domain.StockReservations;

import dasniko.testcontainers.keycloak.KeycloakContainer;

/**
 * Security end to end with a real Keycloak and the real {@code shopflow} realm: tokens are issued by
 * Keycloak and validated by the app as in production (issuer discovery, JWKS, signature, expiry,
 * issuer), then mapped by the app's own configuration ({@code realm_access.roles} to
 * {@code ROLE_*}, the caller named after {@code preferred_username}). Nothing in the security path
 * is mocked; only inventory-service is ({@link StockReservations}), and Kafka is off.
 *
 * <p>This is the test that would catch a broken claim mapping, a realm change (role renamed, user
 * removed, client reconfigured) or a Keycloak upgrade that changes the token. The faster
 * {@code OrderControllerTest} and {@code OrderApiIntegrationTest} simulate tokens instead.
 */
@SpringBootTest(properties = { "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
		"shopflow.outbox-relay-delay=1h" })
@AutoConfigureMockMvc
@AutoConfigureRestTestClient
@Import(KeycloakTestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class KeycloakSecurityIntegrationTest {

	private static final String ALICES_ORDER = """
			{ "customerId": "alice", "lines": [ { "sku": "A", "quantity": 1, "unitPrice": 5.00 } ] }
			""";

	@Autowired
	RestTestClient client;

	@Autowired
	KeycloakContainer keycloak;

	@MockitoBean
	StockReservations stock;

	private String userToken(String username, String password) {
		return this.keycloak.getAccessToken(REALM, WEB_CLIENT, username, password);
	}

	private String alice() {
		return userToken("alice", "alice-pass");
	}

	private String bob() {
		return userToken("bob", "bob-pass");
	}

	private String service() {
		return this.keycloak.getClientCredentialsToken(REALM, SERVICE_CLIENT, SERVICE_SECRET);
	}

	// ---- tokens Keycloak issues are accepted and mapped ---------------------------------

	@Test
	void aCustomerSignedInAtKeycloakPlacesAndReadsHerOwnOrder() {
		String token = alice();

		long id = placeOrder(token, ALICES_ORDER);

		this.client.get().uri("/api/orders/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.customerId").isEqualTo("alice");
	}

	@Test
	void ownershipUsesTheKeycloakUsernameNotTheSubjectId() {
		// The subject of a Keycloak token is the user's UUID. Ownership compares the order's
		// customerId with the caller's name, so it only works if the name is preferred_username.
		this.client.post().uri("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice())
				.contentType(MediaType.APPLICATION_JSON).body(ALICES_ORDER.replace("\"alice\"", "\"bob\""))
				.exchange()
				.expectStatus().isForbidden();
	}

	@Test
	void realmRolesDecideWhoMayReadTheAdminReport() {
		this.client.get().uri("/api/admin/reports/daily-sales").header(HttpHeaders.AUTHORIZATION, "Bearer " + bob())
				.exchange().expectStatus().isOk();
		this.client.get().uri("/api/admin/reports/daily-sales").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice())
				.exchange().expectStatus().isForbidden();
	}

	@Test
	void onlyAnAdminFromKeycloakMayCancel() {
		long id = placeOrder(alice(), ALICES_ORDER);
		String cancel = "{ \"status\": \"CANCELLED\" }";

		this.client.patch().uri("/api/orders/{id}/status", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + alice())
				.header("X-API-Version", "2").contentType(MediaType.APPLICATION_JSON).body(cancel)
				.exchange().expectStatus().isForbidden();
		this.client.patch().uri("/api/orders/{id}/status", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + bob())
				.header("X-API-Version", "2").contentType(MediaType.APPLICATION_JSON).body(cancel)
				.exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.status").isEqualTo("CANCELLED");
	}

	@Test
	void aServiceSignedInWithClientCredentialsActsAsAdmin() {
		this.client.get().uri("/api/admin/reports/daily-sales").header(HttpHeaders.AUTHORIZATION, "Bearer " + service())
				.exchange().expectStatus().isOk();
	}

	// ---- tokens the app must refuse -----------------------------------------------------

	@Test
	void aTokenWithATamperedSignatureIsRejected() {
		String token = alice();
		String[] parts = token.split("\\.");
		// Change a character in the MIDDLE of the signature. Not the last one: in unpadded base64url
		// the last character of a 256-byte RS256 signature carries 4 filler bits, so changing it can
		// decode to the very same bytes, and the "tampered" token would rightly still be valid.
		char[] signature = parts[2].toCharArray();
		int middle = signature.length / 2;
		signature[middle] = (signature[middle] == 'A') ? 'B' : 'A';
		String tampered = parts[0] + "." + parts[1] + "." + new String(signature);
		assertThat(java.util.Base64.getUrlDecoder().decode(new String(signature)))
				.as("the tampered signature must decode to different bytes")
				.isNotEqualTo(java.util.Base64.getUrlDecoder().decode(parts[2]));

		this.client.get().uri("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered)
				.exchange()
				.expectStatus().isUnauthorized()
				.expectHeader().value(HttpHeaders.WWW_AUTHENTICATE,
						(value) -> assertThat(value).contains("invalid_token"));
	}

	@Test
	void aTokenWithEditedClaimsIsRejected() {
		// alice makes herself an admin by rewriting the payload; the signature no longer matches.
		String[] parts = alice().split("\\.");
		String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]),
				java.nio.charset.StandardCharsets.UTF_8);
		String forged = payload.replace("\"customer\"", "\"admin\"");
		assertThat(forged).isNotEqualTo(payload);
		String token = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding()
				.encodeToString(forged.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "." + parts[2];

		this.client.get().uri("/api/admin/reports/daily-sales").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.exchange().expectStatus().isUnauthorized();
	}

	@Test
	void aValidTokenFromAnotherRealmIsRejected() {
		// Correctly signed by the same Keycloak, but for the master realm: wrong issuer and keys.
		String masterToken = this.keycloak.getAccessToken(KeycloakContainer.MASTER_REALM,
				KeycloakContainer.ADMIN_CLI_CLIENT, this.keycloak.getAdminUsername(), this.keycloak.getAdminPassword());

		this.client.get().uri("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + masterToken)
				.exchange().expectStatus().isUnauthorized();
	}

	@Test
	void aWrongPasswordGetsNoTokenAtAll() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> userToken("alice", "wrong"))
				.isInstanceOf(RuntimeException.class);
	}

	// ---- helpers ------------------------------------------------------------------------

	private long placeOrder(String token, String body) {
		OrderV2 created = this.client.post().uri("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).body(body)
				.exchange()
				.expectStatus().isCreated()
				.expectBody(OrderV2.class).returnResult().getResponseBody();
		return Objects.requireNonNull(created).id();
	}

}

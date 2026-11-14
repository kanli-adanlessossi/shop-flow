package com.shopflow.order.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The inventory client's client-credentials token is fetched once and reused until shortly before it expires.
 * One local HTTP server plays both Keycloak's token endpoint and inventory, so the test needs neither Docker
 * nor a Spring context. The beans are built through {@link InventoryClientConfig} itself, as in the app.
 */
class InventoryClientTokenCacheTest {

	private HttpServer server;

	private final AtomicInteger tokenRequests = new AtomicInteger();

	private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();

	/** Lifetime of the tokens the fake token endpoint issues, in seconds. */
	private volatile int expiresIn = 300;

	@BeforeEach
	void startServer() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		this.server.createContext("/token", (exchange) -> {
			int n = this.tokenRequests.incrementAndGet();
			respond(exchange, "{\"access_token\":\"token-" + n + "\",\"token_type\":\"Bearer\",\"expires_in\":"
					+ this.expiresIn + "}");
		});
		this.server.createContext("/api/stock/", (exchange) -> {
			this.authorizationHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
			respond(exchange, "{\"sku\":\"SKU-001\",\"available\":5}");
		});
		this.server.start();
	}

	@AfterEach
	void stopServer() {
		this.server.stop(0);
	}

	@Test
	@DisplayName("one token request serves many inventory calls")
	void tokenIsReused() {
		InventoryClient client = client();

		client.getStock("SKU-001");
		client.getStock("SKU-001");
		client.getStock("SKU-001");

		assertThat(this.tokenRequests).hasValue(1);
		assertThat(this.authorizationHeaders).containsExactly("Bearer token-1", "Bearer token-1", "Bearer token-1");
	}

	@Test
	@DisplayName("a token about to expire is replaced before the call")
	void expiringTokenIsReplaced() {
		// Below the manager's default clock skew of 60 s, so the cached token counts as expired at once.
		this.expiresIn = 30;
		InventoryClient client = client();

		client.getStock("SKU-001");
		client.getStock("SKU-001");

		assertThat(this.tokenRequests).hasValue(2);
		assertThat(this.authorizationHeaders).containsExactly("Bearer token-1", "Bearer token-2");
	}

	private InventoryClient client() {
		String base = "http://localhost:" + this.server.getAddress().getPort();
		InMemoryClientRegistrationRepository registrations = new InMemoryClientRegistrationRepository(
				ClientRegistration.withRegistrationId(InventoryClientConfig.REGISTRATION_ID)
					.clientId("shopflow-service")
					.clientSecret("secret")
					.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
					.tokenUri(base + "/token")
					.build());
		InventoryClientConfig config = new InventoryClientConfig();
		return config.inventoryClient(RestClient.builder(),
				new InventoryProperties(URI.create(base), Duration.ofSeconds(1), Duration.ofSeconds(2)),
				ClientHttpRequestFactoryBuilder.jdk(), HttpClientSettings.defaults(),
				config.inventoryAuthorizedClientManager(registrations,
						new InMemoryOAuth2AuthorizedClientService(registrations)));
	}

	private static void respond(HttpExchange exchange, String json) throws IOException {
		exchange.getRequestBody().readAllBytes();
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

}

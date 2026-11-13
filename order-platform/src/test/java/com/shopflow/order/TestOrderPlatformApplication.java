package com.shopflow.order;

import org.springframework.boot.SpringApplication;

/**
 * Runs the real application locally on the same containers as the integration tests: PostgreSQL
 * and Kafka from {@link TestcontainersConfiguration}, Keycloak with the {@code shopflow} realm from
 * {@link KeycloakTestcontainersConfiguration}. Start it with
 *
 * <pre>
 * ./mvnw -pl order-platform spring-boot:test-run                 # from the repo root
 * ./mvnw -pl order-platform spring-boot:test-run -Dspring-boot.run.profiles=dev   # dev settings, e.g. the webhook secret
 * </pre>
 *
 * or run this {@code main} method from the IDE. Docker is the only requirement; there is no
 * Docker Compose involved.
 *
 * <p>What differs from {@code ./mvnw -pl order-platform spring-boot:run}:
 * <ul>
 * <li>Docker Compose support is switched off, so compose.yaml is not started as well (its Kafka and
 * Keycloak would compete for ports 9092 and 8180).</li>
 * <li>Keycloak is bound to the fixed host port 8180, so http/shopflow-api.http, the k6 load test and
 * the scripts work unchanged (alice/alice-pass, bob/bob-pass, the shopflow-service client).
 * PostgreSQL and Kafka get random ports; only the app connects to them, through
 * {@code @ServiceConnection}.</li>
 * <li>Data does not survive a restart: the containers are removed when the app stops.</li>
 * </ul>
 * inventory-service is still needed for placing orders; use the WireMock stand-in on :8081
 * (deploy/wiremock).
 *
 * <p>Both settings are only defaults: a command-line argument or system property with the same
 * name wins, e.g. {@code --shopflow.local.keycloak-port=8280}.
 */
public final class TestOrderPlatformApplication {

	private TestOrderPlatformApplication() {
	}

	public static void main(String[] args) {
		defaultSystemProperty("spring.docker.compose.enabled", "false");
		defaultSystemProperty(KeycloakTestcontainersConfiguration.HOST_PORT_PROPERTY, "8180");
		SpringApplication.from(OrderPlatformApplication::main)
				.with(TestcontainersConfiguration.class, KeycloakTestcontainersConfiguration.class)
				.run(args);
	}

	/**
	 * System properties rank below command-line arguments, so these stay overridable. Command-line
	 * defaults would not: a repeated {@code --key=value} is joined into one comma-separated value.
	 */
	private static void defaultSystemProperty(String key, String value) {
		if (System.getProperty(key) == null) {
			System.setProperty(key, value);
		}
	}

}

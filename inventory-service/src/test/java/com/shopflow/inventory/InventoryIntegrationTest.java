package com.shopflow.inventory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import dasniko.testcontainers.keycloak.KeycloakContainer;

/**
 * Base of every full-application test. Subclasses add nothing to the context (no extra properties,
 * no {@code @MockitoBean}), so they all share ONE cached Spring context and one set of containers:
 * PostgreSQL, Kafka and Keycloak with the real {@code shopflow} realm. A second context would cost
 * about 20 seconds of Keycloak start-up in CI.
 *
 * <p>Requests commit and nothing is rolled back: each test uses its own reservation ids and SKUs
 * and asserts on those, never on totals across the database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({ TestcontainersConfiguration.class, KeycloakTestcontainersConfiguration.class })
@Testcontainers(disabledWithoutDocker = true)
public abstract class InventoryIntegrationTest {

	@Autowired
	protected RestTestClient http;

	@Autowired
	protected KeycloakContainer keycloak;

	@Autowired
	protected JdbcClient jdbc;

	/** A token for {@code shopflow-service}: realm roles admin and inventory. */
	protected String serviceToken() {
		return this.keycloak.getClientCredentialsToken(KeycloakTestcontainersConfiguration.REALM,
				KeycloakTestcontainersConfiguration.SERVICE_CLIENT, KeycloakTestcontainersConfiguration.SERVICE_SECRET);
	}

	/** A token for the user alice: realm role customer, not inventory. */
	protected String aliceToken() {
		return this.keycloak.getAccessToken(KeycloakTestcontainersConfiguration.REALM,
				KeycloakTestcontainersConfiguration.WEB_CLIENT, "alice", "alice-pass");
	}

}

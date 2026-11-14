package com.shopflow.order;

import java.util.List;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;

import dasniko.testcontainers.keycloak.KeycloakContainer;

/**
 * A real Keycloak for security integration tests (Testcontainers module
 * {@code com.github.dasniko:testcontainers-keycloak}), the same image as compose.yaml, with the
 * same {@code shopflow} realm: {@code keycloak/shopflow-realm.json} is put on the test classpath by
 * the pom, so the tests and the local setup cannot drift apart.
 *
 * <p>Spring Boot has no {@code @ServiceConnection} for Keycloak, so the issuer is wired with a
 * {@link DynamicPropertyRegistrar}: {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}
 * becomes the container's realm URL ({@code http://localhost:<random port>/realms/shopflow}). The
 * app then validates tokens exactly as in production: discovery document, signing keys (JWKS),
 * signature, expiry and issuer.
 *
 * <p>Get tokens from the container bean, from the test host, so their issuer matches that URL:
 * <pre>
 * keycloak.getAccessToken(REALM, WEB_CLIENT, "alice", "alice-pass")
 * keycloak.getClientCredentialsToken(REALM, SERVICE_CLIENT, SERVICE_SECRET)
 * </pre>
 * Starting Keycloak takes about 20 seconds; tests with an identical context share one container.
 * Needs Docker: add {@code @Testcontainers(disabledWithoutDocker = true)} to the test class.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KeycloakTestcontainersConfiguration {

	/** Matches the {@code keycloak} service in compose.yaml. */
	public static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.8.1";

	public static final String REALM = "shopflow";

	/** Public client with the password grant (users sign in). */
	public static final String WEB_CLIENT = "shopflow-web";

	/** Confidential client with client credentials (a service signs in as itself, role admin). */
	public static final String SERVICE_CLIENT = "shopflow-service";

	public static final String SERVICE_SECRET = "shopflow-service-secret";

	/**
	 * Optional fixed host port for Keycloak's HTTP port. Tests leave it unset and get a random free
	 * port. {@link TestOrderPlatformApplication} sets it to 8180 for local runs, the port that
	 * compose.yaml, the .http file and the load test use. Keycloak writes the address the caller
	 * used into the token's issuer, so tools must reach it at exactly that address.
	 */
	public static final String HOST_PORT_PROPERTY = "shopflow.local.keycloak-port";

	@Bean
	KeycloakContainer keycloakContainer(Environment environment) {
		KeycloakContainer keycloak = new KeycloakContainer(KEYCLOAK_IMAGE).withRealmImportFile("/shopflow-realm.json");
		Integer hostPort = environment.getProperty(HOST_PORT_PROPERTY, Integer.class);
		if (hostPort != null) {
			keycloak.setPortBindings(List.of(hostPort + ":8080"));
		}
		return keycloak;
	}

	@Bean
	DynamicPropertyRegistrar keycloakIssuer(KeycloakContainer keycloak) {
		return (registry) -> registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
				() -> keycloak.getIssuerUrl(REALM));
	}

	/**
	 * Where the app signs in as {@code shopflow-service} to call inventory: the container's token
	 * endpoint and the realm's client secret. Story tests, which call the real client, get real tokens.
	 */
	@Bean
	DynamicPropertyRegistrar inventoryClientCredentials(KeycloakContainer keycloak) {
		return (registry) -> {
			registry.add("spring.security.oauth2.client.provider.inventory.token-uri",
					() -> keycloak.getIssuerUrl(REALM) + "/protocol/openid-connect/token");
			registry.add("spring.security.oauth2.client.registration.inventory.client-secret", () -> SERVICE_SECRET);
		};
	}

}

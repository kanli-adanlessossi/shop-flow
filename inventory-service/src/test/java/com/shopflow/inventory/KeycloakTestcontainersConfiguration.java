package com.shopflow.inventory;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

import dasniko.testcontainers.keycloak.KeycloakContainer;

/**
 * A real Keycloak for the security integration tests (Testcontainers module
 * {@code com.github.dasniko:testcontainers-keycloak}), the same image and the same {@code shopflow}
 * realm as compose.yaml: {@code order-platform/keycloak/shopflow-realm.json} is put on the test
 * classpath by the pom, so the tests and the local setup cannot drift apart.
 *
 * <p>Spring Boot has no {@code @ServiceConnection} for Keycloak, so the issuer is wired with a
 * {@link DynamicPropertyRegistrar}. Get tokens from the container bean, from the test host, so their
 * issuer matches the URL the app validates against. Starting Keycloak takes about 20 seconds; tests
 * with an identical context share one container. Needs Docker.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KeycloakTestcontainersConfiguration {

	/** Matches the {@code keycloak} service in compose.yaml. */
	public static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.8.1";

	public static final String REALM = "shopflow";

	/** Public client with the password grant (users sign in). */
	public static final String WEB_CLIENT = "shopflow-web";

	/** Confidential client with client credentials (a service signs in as itself, roles admin and inventory). */
	public static final String SERVICE_CLIENT = "shopflow-service";

	public static final String SERVICE_SECRET = "shopflow-service-secret";

	@Bean
	KeycloakContainer keycloakContainer() {
		return new KeycloakContainer(KEYCLOAK_IMAGE).withRealmImportFile("/shopflow-realm.json");
	}

	@Bean
	DynamicPropertyRegistrar keycloakIssuer(KeycloakContainer keycloak) {
		return (registry) -> registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
				() -> keycloak.getIssuerUrl(REALM));
	}

}

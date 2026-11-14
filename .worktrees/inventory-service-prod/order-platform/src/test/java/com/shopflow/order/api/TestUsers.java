package com.shopflow.order.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Simulated Keycloak users for controller tests, sent with the {@code jwt()} request post-processor.
 *
 * <p>Each call builds a token shaped like Keycloak's access token ({@code preferred_username}
 * and {@code realm_access.roles}, lower-case role names as in the realm) and takes the authorities
 * from the application's own {@link JwtAuthenticationConverter}, the bean Spring Boot builds from
 * {@code spring.security.oauth2.resourceserver.jwt.*}. So a test user gets exactly the authorities
 * (upper-cased, {@code ROLE_} prefix) a real token would get, and changing that mapping in
 * application.yaml changes what the tests see.
 *
 * <p>One difference from production: {@code jwt()} names the caller after the token's
 * <em>subject</em>, whereas the app names the caller after the {@code preferred_username} claim.
 * The subject is therefore set to the user name here; the claim-to-name mapping is covered by
 * {@code SalesReportControllerTest#theConverterNamesTheCallerAfterPreferredUsername}.
 */
final class TestUsers {

	private final JwtAuthenticationConverter converter;

	TestUsers(JwtAuthenticationConverter converter) {
		this.converter = converter;
	}

	/** A signed-in user holding the given Keycloak realm roles, for example {@code as("bob", "admin")}. */
	RequestPostProcessor as(String username, String... realmRoles) {
		return SecurityMockMvcRequestPostProcessors.jwt()
				.jwt(token(username, username, realmRoles))
				.authorities(this::authoritiesOf);
	}

	/** Alice, a customer (realm role {@code customer}). */
	RequestPostProcessor alice() {
		return as("alice", "customer");
	}

	/** Bob, an administrator (realm role {@code admin}). */
	RequestPostProcessor bob() {
		return as("bob", "admin");
	}

	/** A Keycloak-like access token; {@code subject} is an opaque id in the real thing. */
	static Jwt token(String subject, String username, String... realmRoles) {
		Instant now = Instant.now();
		return Jwt.withTokenValue("test-token")
				.header("alg", "none")
				.subject(subject)
				.claim("preferred_username", username)
				.claim("realm_access", Map.of("roles", List.of(realmRoles)))
				.issuedAt(now)
				.expiresAt(now.plus(Duration.ofMinutes(5)))
				.build();
	}

	/** What the application would grant for this token. */
	Collection<GrantedAuthority> authoritiesOf(Jwt jwt) {
		return new ArrayList<>(Objects.requireNonNull(this.converter.convert(jwt)).getAuthorities());
	}

}

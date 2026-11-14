package com.shopflow.order.infra;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every endpoint requires credentials except health. In detail:
 * <ul>
 * <li>{@code GET /actuator/health/**}: open, for load balancers and probes. Details stay hidden
 * (Boot's default {@code show-details: never}).</li>
 * <li>{@code /api/webhooks/payments}: no Keycloak token, because the payment provider cannot get
 * one, but not open either. The controller checks the HMAC signature on every call and answers 401
 * without a valid one (503 while no secret is configured).</li>
 * <li>Everything else, including every other actuator endpoint and unknown paths: a valid Bearer
 * token, and the role or ownership rules on top.</li>
 * </ul>
 * One more URL answers without a token: {@code /.well-known/oauth-protected-resource}, the
 * OAuth 2.0 Protected Resource Metadata (RFC 9728) that Spring Security 7 publishes for every
 * resource server and names in the 401 challenge. It holds only static facts about this API (its
 * URL, that tokens go in the header), cannot be switched off in 7.1, and is meant to be public:
 * clients read it to find out how to get a token.
 *
 * <p>{@code AnonymousAccessTest} calls every mapped endpoint without a token and fails if anything
 * other than the above answers.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity // explicit, so the HttpSecurity builder also exists in controller slice tests
@EnableMethodSecurity // turns on @PreAuthorize / @PostAuthorize on beans
public class SecurityConfig { // public so that controller slice tests in other packages can @Import it

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) {
		http.authorizeHttpRequests(a -> a
				.requestMatchers(pathPattern(HttpMethod.GET, "/actuator/health/**")).permitAll()
				// Authenticated by its HMAC signature in the controller, not by a token (see above).
				.requestMatchers(pathPattern(HttpMethod.POST, "/api/webhooks/payments")).permitAll()
				.requestMatchers(pathPattern("/api/admin/**")).hasRole("ADMIN")
				.anyRequest().authenticated())
				// Callers send "Authorization: Bearer <JWT>" issued by Keycloak; the signature, expiry
				// and issuer are checked with the key set found via spring.security.oauth2.resourceserver.jwt.
				.oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				// Browser calls from the web front end: allowed origins are in CorsConfig.
				.cors(Customizer.withDefaults())
				// CSRF tricks a browser into sending a request with credentials it attaches by itself
				// (cookies). Everything under /api/ is authenticated only by the Authorization header,
				// which a browser never adds on its own, and there are no sessions or cookies, so
				// CSRF protection has nothing to protect there (and the payment webhook is
				// authenticated by its signature). It stays ON for any other path, so a future
				// cookie-based or form page is protected unless someone opts it out on purpose.
				.csrf(csrf -> csrf.ignoringRequestMatchers(pathPattern("/api/**")));
		return http.build();
	}

}

package com.shopflow.inventory.infra;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Machine-to-machine API: callers send {@code Authorization: Bearer <JWT>} from the same Keycloak
 * realm order-platform uses (signature, expiry and issuer are checked against the key set found via
 * {@code spring.security.oauth2.resourceserver.jwt}).
 * <ul>
 * <li>{@code GET /actuator/health/**}: open, for probes.</li>
 * <li>{@code /api/stock/**}: realm role {@code inventory} (granted to the {@code shopflow-service}
 * client). A valid token without it gets 403.</li>
 * <li>Everything else: any valid token.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity // explicit, so the HttpSecurity builder also exists in controller slice tests
public class SecurityConfig { // public so that controller slice tests in other packages can @Import it

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) {
		http.authorizeHttpRequests(a -> a
				.requestMatchers(pathPattern(HttpMethod.GET, "/actuator/health/**")).permitAll()
				.requestMatchers(pathPattern("/api/stock/**")).hasRole("INVENTORY")
				.anyRequest().authenticated())
				.oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				// Everything under /api/ is authenticated only by the Authorization header, which a browser
				// never adds on its own, and there are no sessions or cookies, so CSRF has nothing to protect.
				.csrf(csrf -> csrf.ignoringRequestMatchers(pathPattern("/api/**")));
		return http.build();
	}

}

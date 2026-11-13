package com.shopflow.order.infra;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.shopflow.order.ShopFlowProperties;

/**
 * CORS for the web front end: lets pages served from {@code shopflow.cors.allowed-origins} call the
 * API from a browser. Spring Security uses this bean (named {@code corsConfigurationSource}) through
 * {@code http.cors(...)}, so a preflight {@code OPTIONS} request is answered before authentication
 * and does not need a token.
 *
 * <p>Credentials (cookies) are not allowed: the front end authenticates with an
 * {@code Authorization: Bearer} header, which it adds itself. The payment webhook is called by the
 * provider's servers, not by browsers, so it gets no CORS headers.
 */
@Configuration(proxyBeanMethods = false)
class CorsConfig {

	@Bean
	CorsConfigurationSource corsConfigurationSource(ShopFlowProperties properties) {
		ShopFlowProperties.Cors cors = properties.cors();
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(cors.allowedOrigins());
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		// Only the headers this API reads: not "*", which would also allow any custom header.
		config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-API-Version", "X-Correlation-Id"));
		// Headers JavaScript may read from a response (others are hidden from it by the browser).
		config.setExposedHeaders(List.of("Location", "Deprecation", "Sunset", "Link", "X-Correlation-Id"));
		config.setAllowCredentials(false);
		config.setMaxAge(cors.maxAge());

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/orders/**", config);
		source.registerCorsConfiguration("/api/health-info", config);
		source.registerCorsConfiguration("/api/admin/**", config);
		return source;
	}

}

package com.shopflow.order.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.shopflow.order.domain.StockReservations;

/**
 * Guards the rule "every endpoint needs a token, except health": calls every endpoint the app
 * maps, plus the actuator and well-known paths, WITHOUT a token and lists every answer that is not
 * 401. A new controller method or a widened permitAll that exposes something shows up here, by
 * path, without anyone having to remember to write a test for it.
 */
@SpringBootTest(properties = { "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
		"shopflow.outbox-relay-delay=1h",
		// With a secret configured, an unsigned webhook call must be a 401 like any other.
		"shopflow.payments.webhook-secret=anonymous-access-test-secret" })
@AutoConfigureMockMvc
class AnonymousAccessTest {

	/**
	 * The only answers allowed without credentials. Everything else must be refused (401, or 403
	 * where CSRF protection rejects a write to a non-API path before authentication even starts).
	 */
	private static final Set<String> PUBLIC = Set.of(
			"GET /actuator/health",
			// RFC 9728 metadata, published by Spring Security 7 itself; public by design (SecurityConfig).
			"GET /.well-known/oauth-protected-resource");

	/** Not controller methods, so not in the handler mapping; probed explicitly. */
	private static final List<String> EXTRA = List.of("GET /actuator", "GET /actuator/health", "GET /actuator/info",
			"GET /actuator/metrics", "GET /actuator/env", "GET /.well-known/oauth-protected-resource",
			"GET /error", "GET /no-such-page", "POST /no-such-page");

	@Autowired
	MockMvcTester mvc;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping mappings;

	@MockitoBean
	JwtDecoder jwtDecoder;

	@MockitoBean
	StockReservations stock;

	@Test
	void everythingButHealthAnswers401WithoutAToken() {
		Set<String> endpoints = new TreeSet<>(EXTRA);
		this.mappings.getHandlerMethods().keySet().forEach((info) -> endpoints.addAll(endpoints(info)));

		List<String> open = new ArrayList<>();
		for (String endpoint : endpoints) {
			String[] parts = endpoint.split(" ", 2);
			int status = call(HttpMethod.valueOf(parts[0]), parts[1]);
			boolean refused = status == 401 || status == 403;
			if (PUBLIC.contains(endpoint) ? status != 200 : !refused) {
				open.add(endpoint + " -> " + status);
			}
		}

		assertThat(endpoints).as("probed endpoints").contains("POST /api/webhooks/payments", "GET /api/orders/1",
				"GET /api/health-info", "GET /api/admin/reports/daily-sales");
		assertThat(open).as("answers that break 'only health is public' (expected 401/403, or 200 for "
				+ PUBLIC + ")").isEmpty();
	}

	@Test
	void healthIsOpenForReadingOnly() {
		assertThat(call(HttpMethod.GET, "/actuator/health")).isEqualTo(200);
		assertThat(call(HttpMethod.POST, "/actuator/health")).isIn(401, 403);
	}

	@Test
	void theWebhookNeedsNoTokenButAValidSignature() {
		// No token and no signature: refused by the controller's own check, not by Spring Security.
		var result = this.mvc.post().uri("/api/webhooks/payments").contentType(MediaType.APPLICATION_JSON)
				.content("{}").exchange();
		assertThat(result).hasStatus(401);
		assertThat(result).doesNotContainHeader("WWW-Authenticate");
	}

	private int call(HttpMethod method, String path) {
		var result = this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.request(method, path)
				.header("X-API-Version", "2")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"));
		return result.getResponse().getStatus();
	}

	/** "METHOD /path" for each pattern and method of a mapping; path variables become 1. */
	private static List<String> endpoints(RequestMappingInfo info) {
		Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
		List<String> result = new ArrayList<>();
		for (String pattern : info.getPatternValues()) {
			String path = pattern.replaceAll("\\{[^}]+}", "1");
			if (methods.isEmpty()) {
				result.add("GET " + path);
			}
			methods.forEach((m) -> result.add(m.name() + " " + path));
		}
		return result;
	}

}

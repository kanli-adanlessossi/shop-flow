package com.shopflow.order.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.shopflow.order.domain.OrderDetails;
import com.shopflow.order.domain.OrderDraft;
import com.shopflow.order.domain.OrderNotFoundException;
import com.shopflow.order.domain.OrderService;
import com.shopflow.order.domain.OrderStatus;
import com.shopflow.order.domain.OrderSummary;
import com.shopflow.order.infra.ApiVersioningConfig;
import com.shopflow.order.infra.SecurityConfig;
import com.shopflow.order.infra.ShopFlowApiVersionResolver;

/**
 * Web-layer test of {@link OrderController}: routing, API versions, JSON, validation, error
 * bodies and the access rules, with the real security configuration and {@link OrderAccess}.
 * {@link OrderService} is a mock, so there is no database, cache, Kafka or Keycloak.
 *
 * <p>Callers are simulated with the {@code jwt()} request post-processor through {@link TestUsers}:
 * Keycloak-like tokens (alice is a customer, bob an admin) whose authorities come from the app's
 * own claim mapping, so a rule is tested against the same {@code ROLE_*} authorities a real token
 * gets. Writes work without a CSRF token because the app ignores CSRF under /api/.
 */
@WebMvcTest(OrderController.class)
@Import({ SecurityConfig.class, OrderAccess.class, ApiVersioningConfig.class, ShopFlowApiVersionResolver.class,
		TestMetrics.class })
class OrderControllerTest {

	private static final String NEW_ORDER = """
			{ "customerId": "alice",
			  "discountCode": "WELCOME",
			  "lines": [ { "sku": "A", "quantity": 2, "unitPrice": 10.50 },
			             { "sku": "B", "quantity": 1, "unitPrice": 3.25 } ] }
			""";

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	OrderService orders;

	// Keeps the test away from Keycloak: the resource server needs a decoder bean, but jwt() skips it.
	@MockitoBean
	JwtDecoder jwtDecoder;

	/** The converter Spring Boot builds from spring.security.oauth2.resourceserver.jwt.* in application.yaml. */
	@Autowired
	JwtAuthenticationConverter converter;

	private TestUsers users;

	@BeforeEach
	void createUsers() {
		this.users = new TestUsers(this.converter);
	}

	private RequestPostProcessor alice() {
		return this.users.alice();
	}

	private RequestPostProcessor bob() {
		return this.users.bob();
	}

	private static OrderDetails order(long id, String customerId, OrderStatus status) {
		return new OrderDetails(id, customerId, status, "WELCOME", Instant.parse("2026-11-12T10:00:00Z"),
				List.of(new OrderDetails.Line("A", 2, new BigDecimal("10.50")),
						new OrderDetails.Line("B", 1, new BigDecimal("3.25"))));
	}

	// ---- create -------------------------------------------------------------------------

	@Test
	void createReturns201WithLocationAndVersion2Body() {
		given(this.orders.create(any(OrderDraft.class))).willReturn(order(7, "alice", OrderStatus.PENDING_PAYMENT));

		var result = this.mvc.post().uri("/api/orders")
				.with(alice())
				.header("X-API-Version", "2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(NEW_ORDER);

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).headers().hasValue("Location", "http://localhost/api/orders/7");
		assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(7);
		assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("PENDING_PAYMENT");
		assertThat(result).bodyJson().extractingPath("$.lines[0].lineTotal").isEqualTo(21.00);
		assertThat(result).bodyJson().extractingPath("$.totals.amount").isEqualTo(24.25);
		assertThat(result).bodyJson().extractingPath("$.totals.unitCount").isEqualTo(3);
	}

	@Test
	void createPassesTheRequestToTheServiceAsADraft() {
		given(this.orders.create(any(OrderDraft.class))).willReturn(order(7, "alice", OrderStatus.PENDING_PAYMENT));

		assertThat(this.mvc.post().uri("/api/orders").with(alice()).contentType(MediaType.APPLICATION_JSON)
				.content(NEW_ORDER)).hasStatus(HttpStatus.CREATED);

		ArgumentCaptor<OrderDraft> draft = ArgumentCaptor.forClass(OrderDraft.class);
		verify(this.orders).create(draft.capture());
		assertThat(draft.getValue().customerId()).isEqualTo("alice");
		assertThat(draft.getValue().discountCode()).isEqualTo("WELCOME");
		assertThat(draft.getValue().lines()).extracting(OrderDraft.Line::sku).containsExactly("A", "B");
	}

	@Test
	void createWithNoVersionHeaderUsesTheDefaultVersion2() {
		given(this.orders.create(any(OrderDraft.class))).willReturn(order(7, "alice", OrderStatus.PENDING_PAYMENT));

		var result = this.mvc.post().uri("/api/orders").with(alice()).contentType(MediaType.APPLICATION_JSON)
				.content(NEW_ORDER);

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("PENDING_PAYMENT");
	}

	@Test
	void createVersion1HasNoStatusAndIsMarkedDeprecated() {
		given(this.orders.create(any(OrderDraft.class))).willReturn(order(7, "alice", OrderStatus.PENDING_PAYMENT));

		var result = this.mvc.post().uri("/api/orders").with(alice()).header("X-API-Version", "1")
				.contentType(MediaType.APPLICATION_JSON).content(NEW_ORDER);

		assertThat(result).hasStatus(HttpStatus.CREATED);
		assertThat(result).bodyJson().doesNotHavePath("$.status");
		assertThat(result).headers().containsHeaders("Deprecation", "Sunset");
	}

	@Test
	void createWithSeveralMistakesReturns400WithAPerFieldErrorList() {
		var result = this.mvc.post().uri("/api/orders").with(alice()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{ "customerId": " ", "lines": [ { "sku": "A", "quantity": 0, "unitPrice": 1 } ] }
						""");

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).bodyJson().extractingPath("$.errors[*].field")
				.asList().contains("customerId", "lines[0].quantity");
		verify(this.orders, never()).create(any());
	}

	@Test
	void createWithAnUnsupportedVersionReturns400() {
		assertThat(this.mvc.post().uri("/api/orders").with(alice()).header("X-API-Version", "3")
				.contentType(MediaType.APPLICATION_JSON).content(NEW_ORDER)).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void createInSomeoneElsesNameIsForbiddenForACustomer() {
		var result = this.mvc.post().uri("/api/orders").with(alice()).contentType(MediaType.APPLICATION_JSON)
				.content(NEW_ORDER.replace("\"alice\"", "\"bob\""));

		assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
		verify(this.orders, never()).create(any());
	}

	@Test
	void anAdminMayCreateAnOrderForACustomer() {
		given(this.orders.create(any(OrderDraft.class))).willReturn(order(8, "alice", OrderStatus.PENDING_PAYMENT));

		assertThat(this.mvc.post().uri("/api/orders").with(bob()).contentType(MediaType.APPLICATION_JSON)
				.content(NEW_ORDER)).hasStatus(HttpStatus.CREATED);
	}

	// ---- read ---------------------------------------------------------------------------

	@Test
	void getReturnsTheOwnersOrder() {
		given(this.orders.findById(7L)).willReturn(order(7, "alice", OrderStatus.PENDING_PAYMENT));

		var result = this.mvc.get().uri("/api/orders/7").with(alice()).header("X-API-Version", "2");

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.customerId").isEqualTo("alice");
		assertThat(result).bodyJson().extractingPath("$.createdAt").isEqualTo("2026-11-12T10:00:00Z");
	}

	@Test
	void getOfSomeoneElsesOrderIsForbiddenForACustomer() {
		given(this.orders.findById(9L)).willReturn(order(9, "bob", OrderStatus.PENDING_PAYMENT));

		assertThat(this.mvc.get().uri("/api/orders/9").with(alice())).hasStatus(HttpStatus.FORBIDDEN);
	}

	@Test
	void anAdminMayReadAnyOrder() {
		given(this.orders.findById(7L)).willReturn(order(7, "alice", OrderStatus.PENDING_PAYMENT));

		assertThat(this.mvc.get().uri("/api/orders/7").with(bob())).hasStatusOk();
	}

	@Test
	void aCallerWithNoRoleAtAllIsSignedInButOwnsOnlyTheirOwnOrders() {
		given(this.orders.findById(7L)).willReturn(order(7, "alice", OrderStatus.PENDING_PAYMENT));
		given(this.orders.findById(9L)).willReturn(order(9, "carol", OrderStatus.PENDING_PAYMENT));

		RequestPostProcessor carol = this.users.as("carol");

		assertThat(this.mvc.get().uri("/api/orders/9").with(carol)).hasStatusOk();
		assertThat(this.mvc.get().uri("/api/orders/7").with(carol)).hasStatus(HttpStatus.FORBIDDEN);
	}

	@Test
	void getOfAMissingOrderIs404NotForbidden() {
		given(this.orders.findById(404L)).willThrow(new OrderNotFoundException("404"));

		var result = this.mvc.get().uri("/api/orders/404").with(alice());

		assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(result).bodyJson().extractingPath("$.orderId").isEqualTo("404");
	}

	@Test
	void getWithANonNumericIdReturns400() {
		assertThat(this.mvc.get().uri("/api/orders/abc").with(alice())).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void listReturnsAPageOfSummaries() {
		OrderSummary summary = mock(OrderSummary.class);
		given(summary.getId()).willReturn(7L);
		given(summary.getCustomerId()).willReturn("alice");
		given(summary.getStatus()).willReturn(OrderStatus.CONFIRMED);
		given(summary.getCreatedAt()).willReturn(Instant.parse("2026-11-12T10:00:00Z"));
		given(summary.getTotal()).willReturn(new BigDecimal("24.25"));
		given(this.orders.search(eq(OrderStatus.CONFIRMED), any(Pageable.class)))
				.willReturn(new PageImpl<>(List.of(summary)));

		var result = this.mvc.get().uri("/api/orders?status=CONFIRMED&size=5").with(alice())
				.header("X-API-Version", "2");

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.content[0].id").isEqualTo(7);
		assertThat(result).bodyJson().extractingPath("$.content[0].status").isEqualTo("CONFIRMED");
		assertThat(result).bodyJson().extractingPath("$.content[0].total").isEqualTo(24.25);
		assertThat(result).bodyJson().extractingPath("$.page.totalElements").isEqualTo(1);
	}

	@Test
	void listWithAnInvalidStatusReturns400() {
		assertThat(this.mvc.get().uri("/api/orders?status=NOPE").with(alice())).hasStatus(HttpStatus.BAD_REQUEST);
	}

	// ---- status and delete --------------------------------------------------------------

	@Test
	void anAdminCancelsAnOrder() {
		given(this.orders.findById(7L)).willReturn(order(7, "alice", OrderStatus.PENDING_PAYMENT));
		given(this.orders.updateStatus(7L, OrderStatus.CANCELLED)).willReturn(order(7, "alice", OrderStatus.CANCELLED));

		var result = this.mvc.patch().uri("/api/orders/7/status").with(bob()).header("X-API-Version", "2")
				.contentType(MediaType.APPLICATION_JSON).content("{ \"status\": \"CANCELLED\" }");

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED");
	}

	@Test
	void aCustomerCannotCancelEvenTheirOwnOrder() {
		given(this.orders.findById(7L)).willReturn(order(7, "alice", OrderStatus.PENDING_PAYMENT));

		var result = this.mvc.patch().uri("/api/orders/7/status").with(alice()).header("X-API-Version", "2")
				.contentType(MediaType.APPLICATION_JSON).content("{ \"status\": \"CANCELLED\" }");

		assertThat(result).hasStatus(HttpStatus.FORBIDDEN);
		verify(this.orders, never()).updateStatus(any(Long.class), any());
	}

	@Test
	void aCustomerMayChangeTheStatusOfTheirOwnOrderToSomethingElse() {
		given(this.orders.findById(7L)).willReturn(order(7, "alice", OrderStatus.PENDING_PAYMENT));
		given(this.orders.updateStatus(7L, OrderStatus.SHIPPED)).willReturn(order(7, "alice", OrderStatus.SHIPPED));

		assertThat(this.mvc.patch().uri("/api/orders/7/status").with(alice()).header("X-API-Version", "2")
				.contentType(MediaType.APPLICATION_JSON).content("{ \"status\": \"SHIPPED\" }")).hasStatusOk();
	}

	@Test
	void aMissingStatusReturns400BeforeAnyAccessCheck() {
		assertThat(this.mvc.patch().uri("/api/orders/7/status").with(alice()).header("X-API-Version", "2")
				.contentType(MediaType.APPLICATION_JSON).content("{}")).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void deleteByTheOwnerReturns204() {
		given(this.orders.findById(7L)).willReturn(order(7, "alice", OrderStatus.CANCELLED));

		assertThat(this.mvc.delete().uri("/api/orders/7").with(alice())).hasStatus(HttpStatus.NO_CONTENT);

		verify(this.orders).delete(7L);
	}

	@Test
	void deleteOfSomeoneElsesOrderIsForbidden() {
		given(this.orders.findById(9L)).willReturn(order(9, "bob", OrderStatus.CANCELLED));

		assertThat(this.mvc.delete().uri("/api/orders/9").with(alice())).hasStatus(HttpStatus.FORBIDDEN);

		verify(this.orders, never()).delete(any(Long.class));
	}

	// ---- who may call at all ------------------------------------------------------------

	@Test
	void aRequestWithoutATokenIs401() {
		assertThat(this.mvc.get().uri("/api/orders/7")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(this.mvc.post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON).content(NEW_ORDER))
				.hasStatus(HttpStatus.UNAUTHORIZED);
	}

}

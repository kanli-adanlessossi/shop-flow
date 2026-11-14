package com.shopflow.order.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.shopflow.order.domain.DailySales;
import com.shopflow.order.infra.ApiVersioningConfig;
import com.shopflow.order.infra.DailySalesQuery;
import com.shopflow.order.infra.SecurityConfig;
import com.shopflow.order.infra.ShopFlowApiVersionResolver;

/**
 * The admin-only sales report, and the mapping from Keycloak token claims to the caller's name and
 * authorities. Callers are simulated with the {@code jwt()} request post-processor ({@link TestUsers}).
 */
@WebMvcTest(SalesReportController.class)
@Import({ SecurityConfig.class, ApiVersioningConfig.class, ShopFlowApiVersionResolver.class, TestMetrics.class })
class SalesReportControllerTest {

	private static final String REPORT = "/api/admin/reports/daily-sales?from=2026-11-10&to=2026-11-12";

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	DailySalesQuery query;

	@MockitoBean
	JwtDecoder jwtDecoder;

	@Autowired
	JwtAuthenticationConverter converter;

	private TestUsers users;

	@BeforeEach
	void createUsers() {
		this.users = new TestUsers(this.converter);
	}

	// ---- who may read the report --------------------------------------------------------

	@Test
	void anAdminGetsTheReportWithZeroRowsForDaysWithoutSales() {
		given(this.query.dailySales(LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-12")))
				.willReturn(List.of(new DailySales(LocalDate.parse("2026-11-11"), 2, 5, new BigDecimal("48.50"))));

		var result = this.mvc.get().uri(REPORT).with(this.users.bob()).header("X-API-Version", "2");

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.days.length()").isEqualTo(3);
		assertThat(result).bodyJson().extractingPath("$.days[0].orderCount").isEqualTo(0);
		assertThat(result).bodyJson().extractingPath("$.days[1].revenue").isEqualTo(48.50);
		assertThat(result).bodyJson().extractingPath("$.totals.orderCount").isEqualTo(2);
		assertThat(result).bodyJson().extractingPath("$.totals.revenue").isEqualTo(48.50);
	}

	@Test
	void aCustomerIsForbidden() {
		assertThat(this.mvc.get().uri(REPORT).with(this.users.alice())).hasStatus(HttpStatus.FORBIDDEN);

		verify(this.query, never()).dailySales(any(), any());
	}

	@Test
	void aCallerWithBothRolesIsAllowedBecauseAdminIsEnough() {
		given(this.query.dailySales(any(), any())).willReturn(List.of());

		assertThat(this.mvc.get().uri(REPORT).with(this.users.as("dave", "customer", "admin"))).hasStatusOk();
	}

	@Test
	void aSignedInCallerWithoutRolesIsForbidden() {
		assertThat(this.mvc.get().uri(REPORT).with(this.users.as("carol"))).hasStatus(HttpStatus.FORBIDDEN);
	}

	@Test
	void aRequestWithoutATokenIs401() {
		assertThat(this.mvc.get().uri(REPORT)).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void anAdminWithAWrongRangeGets400() {
		assertThat(this.mvc.get().uri("/api/admin/reports/daily-sales?from=2026-11-30&to=2026-11-01")
				.with(this.users.bob())).hasStatus(HttpStatus.BAD_REQUEST);
	}

	// ---- claim mapping (spring.security.oauth2.resourceserver.jwt.* in application.yaml) ----

	@Test
	void theConverterNamesTheCallerAfterPreferredUsername() {
		// A real Keycloak subject is an opaque id; the app names the caller by preferred_username.
		Jwt token = TestUsers.token(UUID.randomUUID().toString(), "alice", "customer");

		var authentication = this.converter.convert(token);

		assertThat(authentication).isNotNull();
		assertThat(authentication.getName()).isEqualTo("alice");
	}

	@Test
	void theConverterTurnsRealmRolesIntoUpperCaseRoleAuthorities() {
		Jwt token = TestUsers.token(UUID.randomUUID().toString(), "dave", "customer", "admin");

		var authentication = this.converter.convert(token);

		assertThat(authentication).isNotNull();
		// Spring Security 7 also adds FACTOR_BEARER to bearer-token logins (the factor marker of its MFA model).
		assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
				.containsExactlyInAnyOrder("ROLE_CUSTOMER", "ROLE_ADMIN", "FACTOR_BEARER");
	}

}

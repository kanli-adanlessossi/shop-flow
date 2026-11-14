package com.shopflow.order.stories;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

@DisplayName("US-6 Daily sales report")
class Us06DailySalesReportStoryTest extends StoryTestSupport {

	private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
	};

	@Test
	@DisplayName("today's row counts new orders, their units and revenue, but not cancelled ones")
	void countsTodaysSales() {
		String today = LocalDate.now(ZoneOffset.UTC).toString();
		Map<String, Object> before = todayRow(today);

		placeAlicesOrder();                                        // 3 units, 24.25
		long cancelled = idOf(placeAlicesOrder());                 // not counted once cancelled
		patchStatus(bob(), cancelled, "CANCELLED").expectStatus().isOk();

		Map<String, Object> after = todayRow(today);
		assertThat(number(after, "orderCount") - number(before, "orderCount")).isEqualTo(1);
		assertThat(number(after, "unitCount") - number(before, "unitCount")).isEqualTo(3);
		assertThat(money(after).subtract(money(before))).isEqualByComparingTo("24.25");
	}

	@Test
	@DisplayName("one row per day, zero rows for days without sales, and totals")
	void oneRowPerDay() {
		get(bob(), "/api/admin/reports/daily-sales?from=2020-01-01&to=2020-01-03")
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.days.length()").isEqualTo(3)
				.jsonPath("$.days[0].salesDate").isEqualTo("2020-01-01")
				.jsonPath("$.days[2].orderCount").isEqualTo(0)
				.jsonPath("$.days[2].revenue").isEqualTo(0.00)
				.jsonPath("$.totals.orderCount").isEqualTo(0);
	}

	@Test
	@DisplayName("admins only: customers get 403, a service with the admin role may read it")
	void adminsOnly() {
		get(alice(), "/api/admin/reports/daily-sales").expectStatus().isForbidden();
		get(service(), "/api/admin/reports/daily-sales").expectStatus().isOk();
	}

	@Test
	@DisplayName("a reversed or over-long range is 400; version 1 does not have the report")
	void invalidRequests() {
		String token = bob();
		get(token, "/api/admin/reports/daily-sales?from=2026-11-30&to=2026-11-01").expectStatus().isBadRequest();
		get(token, "/api/admin/reports/daily-sales?from=2020-01-01&to=2026-11-30").expectStatus().isBadRequest();
		this.http.get().uri("/api/admin/reports/daily-sales").header("Authorization", "Bearer " + token)
				.header("X-API-Version", "1").exchange()
				.expectStatus().isNotFound();
	}

	private Map<String, Object> todayRow(String today) {
		Map<String, Object> report = Objects.requireNonNull(
				get(bob(), "/api/admin/reports/daily-sales?from={d}&to={d}", today, today)
						.expectStatus().isOk()
						.expectBody(JSON).returnResult().getResponseBody());
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> days = (List<Map<String, Object>>) report.get("days");
		assertThat(days).hasSize(1);
		return days.get(0);
	}

	private static long number(Map<String, Object> row, String key) {
		return ((Number) Objects.requireNonNull(row.get(key))).longValue();
	}

	private static BigDecimal money(Map<String, Object> row) {
		return new BigDecimal(String.valueOf(row.get("revenue")));
	}

}

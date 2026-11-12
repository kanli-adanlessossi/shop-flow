package com.shopflow.order.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.shopflow.order.domain.DailySales;
import com.shopflow.order.infra.DailySalesQuery;

/**
 * Sales reports, meant for administrators. Everything is under {@code /api/admin} so access
 * can later be restricted with a single rule. There is no authentication yet.
 */
@RestController
@RequestMapping("/api/admin/reports")
public class SalesReportController {

	static final int DEFAULT_DAYS = 30;

	static final int MAX_DAYS = 366;

	/** Zero with two decimals, so money looks the same on days without sales. */
	private static final BigDecimal NO_MONEY = BigDecimal.ZERO.setScale(2);

	private final DailySalesQuery query;

	public SalesReportController(DailySalesQuery query) {
		this.query = query;
	}

	/**
	 * Orders, units and revenue per day (UTC), both ends inclusive, cancelled orders excluded.
	 * Defaults to the last 30 days. Days without sales are returned as zero rows so the series
	 * has no gaps. Available from API version 2 onwards.
	 */
	@GetMapping(path = "/daily-sales", version = "2+")
	public DailySalesReport dailySales(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate to) {
		LocalDate end = (to != null) ? to : LocalDate.now(ZoneOffset.UTC);
		LocalDate start = (from != null) ? from : end.minusDays(DEFAULT_DAYS - 1L);
		if (start.isAfter(end)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'from' must not be after 'to'.");
		}
		if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"The range must not be longer than " + MAX_DAYS + " days.");
		}
		Map<LocalDate, DailySales> bySales = new HashMap<>();
		this.query.dailySales(start, end).forEach(day -> bySales.put(day.salesDate(), day));
		List<DailySales> days = new ArrayList<>();
		for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
			days.add(bySales.getOrDefault(day, new DailySales(day, 0, 0, NO_MONEY)));
		}
		return DailySalesReport.of(start, end, days);
	}

	public record DailySalesReport(LocalDate from, LocalDate to, List<DailySales> days, Totals totals) {

		public record Totals(long orderCount, long unitCount, BigDecimal revenue) {
		}

		static DailySalesReport of(LocalDate from, LocalDate to, List<DailySales> days) {
			long orders = days.stream().mapToLong(DailySales::orderCount).sum();
			long units = days.stream().mapToLong(DailySales::unitCount).sum();
			BigDecimal revenue = days.stream().map(DailySales::revenue).reduce(NO_MONEY, BigDecimal::add);
			return new DailySalesReport(from, to, days, new Totals(orders, units, revenue));
		}

	}

}

package com.shopflow.order.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.shopflow.order.domain.OrderDetails;
import com.shopflow.order.domain.OrderStatus;
import com.shopflow.order.domain.OrderSummary;

/** Version 2 representation: version 1 plus {@code status}, a total per line and order {@code totals}. */
public record OrderV2(
		Long id,
		String customerId,
		@Nullable String discountCode,
		Instant createdAt,
		OrderStatus status,
		List<Line> lines,
		Totals totals) {

	public record Line(String sku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
	}

	/** {@code amount} is the sum of quantity times unit price over all lines. */
	public record Totals(int lineCount, int unitCount, BigDecimal amount) {
	}

	/** One row of the version 2 list. */
	public record Item(Long id, String customerId, OrderStatus status, Instant createdAt, BigDecimal total) {

		static Item from(OrderSummary summary) {
			return new Item(summary.getId(), summary.getCustomerId(), summary.getStatus(),
					summary.getCreatedAt(), summary.getTotal());
		}

	}

	static OrderV2 from(OrderDetails order) {
		List<Line> lines = order.lines().stream()
				.map(line -> new Line(line.sku(), line.quantity(), line.unitPrice(),
						line.unitPrice().multiply(BigDecimal.valueOf(line.quantity()))))
				.toList();
		int units = lines.stream().mapToInt(Line::quantity).sum();
		BigDecimal amount = lines.stream().map(Line::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
		return new OrderV2(
				order.id(),
				order.customerId(),
				order.discountCode(),
				order.createdAt(),
				order.status(),
				lines,
				new Totals(lines.size(), units, amount));
	}

}

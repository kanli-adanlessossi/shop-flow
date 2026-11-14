package com.shopflow.order.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.shopflow.order.domain.OrderDetails;
import com.shopflow.order.domain.OrderSummary;

/** Version 1 representation: no status and no totals. */
public record OrderV1(
		Long id,
		String customerId,
		@Nullable String discountCode,
		Instant createdAt,
		List<Line> lines) {

	public record Line(String sku, int quantity, BigDecimal unitPrice) {
	}

	/** One row of the version 1 list. */
	public record Item(Long id, String customerId, Instant createdAt) {

		static Item from(OrderSummary summary) {
			return new Item(summary.getId(), summary.getCustomerId(), summary.getCreatedAt());
		}

	}

	static OrderV1 from(OrderDetails order) {
		return new OrderV1(
				order.id(),
				order.customerId(),
				order.discountCode(),
				order.createdAt(),
				order.lines().stream().map(line -> new Line(line.sku(), line.quantity(), line.unitPrice())).toList());
	}

}

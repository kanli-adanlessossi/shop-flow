package com.shopflow.order.domain;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Immutable view of an order with its lines. This is what gets cached, so it
 * never exposes lazy JPA state.
 */
public record OrderDetails(
		Long id,
		String customerId,
		OrderStatus status,
		@Nullable String discountCode,
		Instant createdAt,
		List<Line> lines) implements Serializable {

	public record Line(String sku, int quantity, BigDecimal unitPrice) implements Serializable {

		/**
		 * Java deserialization of a record fills missing fields with null and then calls this
		 * constructor, so a cache entry written before {@code unitPrice} existed is rejected
		 * here instead of leaking a null into the API.
		 */
		public Line {
			Objects.requireNonNull(unitPrice, "unitPrice");
		}

	}

	static OrderDetails from(Order order) {
		Long id = order.getId();
		if (id == null) {
			throw new IllegalStateException("Order has not been saved yet");
		}
		return new OrderDetails(
				id,
				order.getCustomerId(),
				order.getStatus(),
				order.getDiscountCode(),
				order.getCreatedAt(),
				order.getLines().stream().map(line -> new Line(line.getSku(), line.getQuantity(), line.getUnitPrice())).toList());
	}

}

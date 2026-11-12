package com.shopflow.order;

import java.math.BigDecimal;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * An order placed by a customer. Everything is non-null by default
 * (see package-info); only the discount code is optional.
 */
public record Order(
		String id,
		String customerId,
		List<String> itemSkus,
		BigDecimal total,
		@Nullable String discountCode) {
}

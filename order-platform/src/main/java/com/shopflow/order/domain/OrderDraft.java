package com.shopflow.order.domain;

import java.math.BigDecimal;
import java.util.List;

import org.jspecify.annotations.Nullable;

/** The editable content of an order, used to create one or to replace an existing one. */
public record OrderDraft(String customerId, @Nullable String discountCode, List<Line> lines) {

	public record Line(String sku, int quantity, BigDecimal unitPrice) {
	}

}

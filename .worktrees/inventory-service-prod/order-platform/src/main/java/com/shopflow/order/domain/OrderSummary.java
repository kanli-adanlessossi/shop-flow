package com.shopflow.order.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Closed interface projection for order list views. The lines are not loaded;
 * {@code total} is summed by the database.
 */
public interface OrderSummary {

	Long getId();

	String getCustomerId();

	OrderStatus getStatus();

	Instant getCreatedAt();

	BigDecimal getTotal();

}

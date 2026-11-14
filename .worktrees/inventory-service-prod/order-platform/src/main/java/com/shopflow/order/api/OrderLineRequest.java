package com.shopflow.order.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** One line of an order request. The unit price is not part of it: the server sets it. */
public record OrderLineRequest(
		@NotBlank String sku,
		@Positive int quantity) {
}

package com.shopflow.order.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record OrderLineRequest(
		@NotBlank String sku,
		@Positive int quantity,
		@NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal unitPrice) {
}

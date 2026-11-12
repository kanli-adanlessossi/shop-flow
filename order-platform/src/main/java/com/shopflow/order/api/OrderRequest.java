package com.shopflow.order.api;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.shopflow.order.domain.OrderDraft;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/** Request body for creating (POST) and replacing (PUT) an order. The same in every API version. */
public record OrderRequest(
		@NotBlank String customerId,
		@NotEmpty List<@Valid OrderLineRequest> lines,
		@Nullable String discountCode) {

	OrderDraft toDraft() {
		return new OrderDraft(
				this.customerId,
				this.discountCode,
				this.lines.stream()
						.map(line -> new OrderDraft.Line(line.sku(), line.quantity(), line.unitPrice()))
						.toList());
	}

}

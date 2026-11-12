package com.shopflow.order;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * Externalised configuration bound from the {@code shopflow.*} properties.
 */
@ConfigurationProperties("shopflow")
@Validated
public record ShopFlowProperties(
		@NotNull Duration paymentTimeout,
		@NotNull Duration cancelAfter,
		@DefaultValue Map<String, Boolean> features) {
}

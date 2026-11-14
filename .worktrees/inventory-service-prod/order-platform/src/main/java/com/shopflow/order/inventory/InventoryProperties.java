package com.shopflow.order.inventory;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/** Settings for the connection to inventory-service ({@code shopflow.inventory.*}). */
@Validated
@ConfigurationProperties("shopflow.inventory")
public record InventoryProperties(
		@NotNull URI baseUrl,
		@DefaultValue("1s") Duration connectTimeout,
		@DefaultValue("2s") Duration readTimeout) {
}

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
		@DefaultValue Map<String, Boolean> features,
		/*
		 * How long the inbox remembers handled event ids. It must be longer than the time an old
		 * event can still be redelivered, which is the topic's retention (Kafka's default is 7 days).
		 */
		@DefaultValue("14d") Duration processedEventsRetention,
		/** How long rows already sent to Kafka stay in the outbox table (for debugging) before they are purged. */
		@DefaultValue("7d") Duration outboxRetention) {
}

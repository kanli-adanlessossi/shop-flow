package com.shopflow.order;

import java.time.Duration;
import java.util.List;
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
		@DefaultValue("7d") Duration outboxRetention,
		/** Which browser front ends may call the API (CORS). */
		@DefaultValue Cors cors) {

	/**
	 * @param allowedOrigins exact origins (scheme, host, port; no path, no trailing slash) whose pages
	 * may call the API from a browser. The defaults are the local dev servers of common front-end
	 * tools. Set {@code SHOPFLOW_CORS_ALLOWED_ORIGINS} (comma separated) for a real deployment.
	 * @param maxAge how long a browser may remember the answer to a preflight request
	 */
	public record Cors(
			@DefaultValue({ "http://localhost:3000", "http://localhost:5173" }) List<String> allowedOrigins,
			@DefaultValue("1h") Duration maxAge) {
	}

}

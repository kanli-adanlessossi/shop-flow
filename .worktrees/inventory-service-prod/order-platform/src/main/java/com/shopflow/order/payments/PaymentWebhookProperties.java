package com.shopflow.order.payments;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the payment provider's webhook ({@code shopflow.payments.*}).
 *
 * @param webhookSecret shared secret used to sign webhook bodies. Not committed anywhere: set it with
 * the {@code PAYMENT_WEBHOOK_SECRET} environment variable. While it is empty, the webhook endpoint
 * refuses every request.
 * @param webhookTolerance how far a webhook's timestamp may be from the server's clock; older or
 * newer requests are rejected, so a captured request cannot be replayed later
 */
@ConfigurationProperties("shopflow.payments")
public record PaymentWebhookProperties(
		@DefaultValue("") String webhookSecret,
		@DefaultValue("5m") Duration webhookTolerance) {

	/** A non-empty secret shorter than this is refused at startup. Use a random value of 32 or more characters. */
	public static final int MIN_SECRET_LENGTH = 16;

	public PaymentWebhookProperties {
		// Empty stays allowed: the endpoint then answers 503 (fails closed). A weak secret would be accepted
		// silently and make the signatures guessable, so it stops the application from starting.
		if (!webhookSecret.isEmpty() && webhookSecret.length() < MIN_SECRET_LENGTH) {
			throw new IllegalArgumentException("shopflow.payments.webhook-secret must be empty (webhook disabled) or at least "
					+ MIN_SECRET_LENGTH + " characters long");
		}
	}
}

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
}

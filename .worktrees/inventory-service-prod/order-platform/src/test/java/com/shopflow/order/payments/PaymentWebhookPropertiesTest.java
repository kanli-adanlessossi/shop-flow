package com.shopflow.order.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class PaymentWebhookPropertiesTest {

	@Test
	void anEmptySecretIsAllowedAndKeepsTheWebhookDisabled() {
		assertThat(new PaymentWebhookProperties("", Duration.ofMinutes(5)).webhookSecret()).isEmpty();
	}

	@Test
	void aShortSecretStopsTheApplicationFromStarting() {
		assertThatThrownBy(() -> new PaymentWebhookProperties("short", Duration.ofMinutes(5)))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 16");
	}

	@Test
	void aSecretOfTheMinimumLengthIsAccepted() {
		String secret = "x".repeat(PaymentWebhookProperties.MIN_SECRET_LENGTH);
		assertThat(new PaymentWebhookProperties(secret, Duration.ofMinutes(5)).webhookSecret()).isEqualTo(secret);
	}
}

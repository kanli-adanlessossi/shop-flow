package com.shopflow.order.payments;

/** No webhook secret is configured, so no request can be verified and none is accepted. */
public class WebhookNotConfiguredException extends RuntimeException {

	public WebhookNotConfiguredException() {
		super("shopflow.payments.webhook-secret is not set");
	}

}

package com.shopflow.order.payments;

/**
 * The webhook could not be shown to come from the payment provider. The reason is for the log only;
 * the caller is told nothing beyond "unauthorized", so it learns nothing about which check failed.
 */
public class InvalidWebhookSignatureException extends RuntimeException {

	public InvalidWebhookSignatureException(String reason) {
		super(reason);
	}

}

package com.shopflow.order.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Tells a customer that their unpaid order was cancelled. Runs on the {@code applicationTaskExecutor}
 * so a slow or failing notification never delays the cleanup job.
 *
 * <p>There is no mail or messaging channel in the project yet, so for now the notice is only logged.
 * Replace the body of {@link #sendCancellationNotice} with the real call; keep it free of anything
 * that must be part of the caller's transaction, because it runs on another thread.
 */
@Component
public class CancellationNotifier {

	private static final Logger LOGGER = LoggerFactory.getLogger(CancellationNotifier.class);

	@Async
	public void sendCancellationNotice(long orderId, String customerId) {
		try {
			LOGGER.info("Sending cancellation notice for order {} to customer {}", orderId, customerId);
		}
		catch (RuntimeException ex) {
			// Nobody awaits an @Async void method: log here so a failure is not lost.
			LOGGER.warn("Could not send cancellation notice for order {}", orderId, ex);
		}
	}

}

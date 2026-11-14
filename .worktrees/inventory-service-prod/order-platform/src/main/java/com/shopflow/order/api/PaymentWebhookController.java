package com.shopflow.order.api;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.shopflow.order.domain.OrderService;
import com.shopflow.order.domain.PaymentOutcome;
import com.shopflow.order.payments.PaymentEvent;
import com.shopflow.order.payments.PaymentWebhookProperties;
import com.shopflow.order.payments.WebhookNotConfiguredException;
import com.shopflow.order.payments.WebhookSignature;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives the payment provider's webhooks. This endpoint is public by necessity (the provider is not a
 * logged-in user), so the signature is the only thing standing between the internet and order state: it is
 * checked on the raw bytes before the body is parsed or any data is read.
 *
 * <p>Response codes follow what providers expect: 2xx means "received, do not send again" and is returned for
 * everything that was understood, including events that need no action; 4xx means the request itself is wrong;
 * a 5xx makes the provider retry, which is right for a failure on our side.
 *
 * <ul>
 * <li>200: handled (the body says how, see {@link PaymentOutcome}), or ignored because the type needs no action</li>
 * <li>400: signed, but not a webhook body we can read</li>
 * <li>401: signature missing, wrong or too old</li>
 * <li>404: no such order</li>
 * <li>409: the order was cancelled before the payment arrived (needs a refund), or another delivery of
 * the same event is being processed right now (retry shortly)</li>
 * <li>422: the amount paid is not the order total</li>
 * <li>503: no webhook secret configured, so nothing can be verified</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/webhooks")
public class PaymentWebhookController {

	private static final Logger LOGGER = LoggerFactory.getLogger(PaymentWebhookController.class);

	static final String SUCCEEDED = "payment.succeeded";

	private final OrderService orders;

	private final PaymentWebhookProperties properties;

	private final JsonMapper mapper;

	private final Clock clock;

	public PaymentWebhookController(OrderService orders, PaymentWebhookProperties properties, JsonMapper mapper,
			Clock clock) {
		this.orders = orders;
		this.properties = properties;
		this.mapper = mapper;
		this.clock = clock;
	}

	@PostMapping("/payments")
	ResponseEntity<Map<String, String>> receive(
			@RequestHeader(name = WebhookSignature.HEADER, required = false) @Nullable String signature,
			@RequestBody byte[] body) {
		if (this.properties.webhookSecret().isBlank()) {
			throw new WebhookNotConfiguredException();
		}
		WebhookSignature.verify(this.properties.webhookSecret(), this.properties.webhookTolerance(), signature, body,
				this.clock.instant());

		PaymentEvent event = parse(body);
		String eventId = require(event.eventId(), "eventId");
		String type = require(event.type(), "type");

		if (!SUCCEEDED.equals(type)) {
			// payment.failed and any type added later: nothing to do. An unpaid order is cancelled by the
			// cleanup job after shopflow.cancel-after, which also gives its stock back.
			LOGGER.info("Ignoring payment webhook {} of type {}", eventId, type);
			return result("IGNORED");
		}
		PaymentEvent.Data data = event.data();
		if (data == null || data.orderId() == null || data.amount() == null || data.amount().signum() <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"payment.succeeded needs data.orderId and a positive data.amount");
		}
		BigDecimal amount = data.amount();
		PaymentOutcome outcome;
		try {
			outcome = this.orders.confirmPayment(data.orderId(), amount, eventId);
		}
		catch (DuplicateKeyException ex) {
			// Two deliveries of this event ran at the same moment and this one lost the race at the inbox's
			// unique key. The other may still fail and roll back, so "duplicate" would not be safe to say:
			// ask the provider to try again, which then finds the finished result.
			LOGGER.info("Payment webhook {} is being handled by another delivery; asked the provider to retry", eventId);
			throw new ResponseStatusException(HttpStatus.CONFLICT, "This event is being processed; retry shortly");
		}
		LOGGER.info("Payment webhook {} for order {} (payment {}): {}", eventId, data.orderId(), data.paymentId(), outcome);
		return result(outcome.name());
	}

	private PaymentEvent parse(byte[] body) {
		try {
			return this.mapper.readValue(body, PaymentEvent.class);
		}
		catch (JacksonException ex) {
			// The signature was valid, so this is the provider's mistake, not an attacker's.
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The webhook body is not valid JSON of the expected shape");
		}
	}

	private static String require(@Nullable String value, String name) {
		if (value == null || value.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The webhook has no " + name);
		}
		return value;
	}

	private static ResponseEntity<Map<String, String>> result(String outcome) {
		return ResponseEntity.ok(Map.of("result", outcome));
	}

}

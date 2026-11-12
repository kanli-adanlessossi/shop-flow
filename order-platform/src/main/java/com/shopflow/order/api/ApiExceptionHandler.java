package com.shopflow.order.api;

import java.net.URI;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.shopflow.order.domain.InsufficientStockException;
import com.shopflow.order.domain.LatePaymentException;
import com.shopflow.order.domain.PaymentAmountMismatchException;
import com.shopflow.order.payments.InvalidWebhookSignatureException;
import com.shopflow.order.payments.WebhookNotConfiguredException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import com.shopflow.order.domain.InvalidSortException;
import com.shopflow.order.domain.InventoryUnavailableException;
import com.shopflow.order.domain.OrderNotFoundException;
import com.shopflow.order.domain.OrderNotModifiableException;

/**
 * Central place for turning exceptions into RFC 9457 problem details.
 * The base class already maps Spring MVC's own exceptions (validation failures,
 * missing parameters, unsupported media types, invalid API versions, ...).
 * Add {@code @ExceptionHandler} methods here for application exceptions.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

	private static final URI INSUFFICIENT_STOCK_TYPE =
			URI.create("https://example.com/problems/insufficient-stock");

	private static final URI INVENTORY_UNAVAILABLE_TYPE =
			URI.create("https://example.com/problems/inventory-unavailable");

	/** Counter for payments that arrived after the order was cancelled; any increase needs a person. */
	public static final String LATE_PAYMENTS = "shopflow.payments.late";

	private static final URI WEBHOOK_SIGNATURE_TYPE =
			URI.create("https://example.com/problems/webhook-signature-invalid");

	private static final URI LATE_PAYMENT_TYPE =
			URI.create("https://example.com/problems/late-payment");

	private static final URI AMOUNT_MISMATCH_TYPE =
			URI.create("https://example.com/problems/payment-amount-mismatch");

	private static final URI ORDER_NOT_FOUND_TYPE =
			URI.create("https://example.com/problems/order-not-found");

	private static final URI ORDER_NOT_MODIFIABLE_TYPE =
			URI.create("https://example.com/problems/order-not-modifiable");

	private final MeterRegistry meters;

	public ApiExceptionHandler(MeterRegistry meters) {
		this.meters = meters;
	}

	/** Adds a per-field {@code errors} list to the standard 400 problem detail. */
	@Override
	protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
			MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
				.toList();
		ex.getBody().setProperty("errors", errors);
		return super.handleMethodArgumentNotValid(ex, headers, status, request);
	}

	public record FieldViolation(String field, @Nullable String message) {
	}

	/** An unknown {@code sort} property in a paged request is a client error, not a 500. */
	@ExceptionHandler(InvalidSortException.class)
	ProblemDetail handleInvalidSort(InvalidSortException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(OrderNotModifiableException.class)
	ProblemDetail handleOrderNotModifiable(OrderNotModifiableException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setType(ORDER_NOT_MODIFIABLE_TYPE);
		problem.setTitle("Order cannot be changed");
		problem.setProperty("orderId", ex.getOrderId());
		problem.setProperty("orderStatus", ex.getStatus());
		return problem;
	}

	@ExceptionHandler(InsufficientStockException.class)
	ProblemDetail handleInsufficientStock(InsufficientStockException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setType(INSUFFICIENT_STOCK_TYPE);
		problem.setTitle("Insufficient stock");
		return problem;
	}

	/** The cause (host, status) is in the logs, not in the response. */
	@ExceptionHandler(InventoryUnavailableException.class)
	ProblemDetail handleInventoryUnavailable(InventoryUnavailableException ex) {
		LOGGER.warn("Inventory unavailable: {}", ex.getMessage(), ex);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
				"Stock could not be checked right now. Please try again later.");
		problem.setType(INVENTORY_UNAVAILABLE_TYPE);
		problem.setTitle("Inventory unavailable");
		return problem;
	}

	/** The caller learns only that authentication failed; the reason goes to the log. */
	@ExceptionHandler(InvalidWebhookSignatureException.class)
	ProblemDetail handleInvalidWebhookSignature(InvalidWebhookSignatureException ex) {
		LOGGER.warn("Rejected a payment webhook: {}", ex.getMessage());
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
				"The request could not be authenticated.");
		problem.setType(WEBHOOK_SIGNATURE_TYPE);
		problem.setTitle("Invalid webhook signature");
		return problem;
	}

	@ExceptionHandler(WebhookNotConfiguredException.class)
	ProblemDetail handleWebhookNotConfigured(WebhookNotConfiguredException ex) {
		LOGGER.error("Rejected a payment webhook: {}", ex.getMessage());
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
				"Webhooks are not enabled on this server.");
		problem.setTitle("Webhook not configured");
		return problem;
	}

	/** Money was taken for an order that no longer exists in a payable state: somebody has to refund it. */
	@ExceptionHandler(LatePaymentException.class)
	ProblemDetail handleLatePayment(LatePaymentException ex) {
		LOGGER.error("LATE PAYMENT, refund needed: {}", ex.getMessage());
		Counter.builder(LATE_PAYMENTS).description("Payments that arrived for an already cancelled order")
				.register(this.meters).increment();
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setType(LATE_PAYMENT_TYPE);
		problem.setTitle("Order already cancelled");
		problem.setProperty("orderId", ex.getOrderId());
		return problem;
	}

	@ExceptionHandler(PaymentAmountMismatchException.class)
	ProblemDetail handlePaymentAmountMismatch(PaymentAmountMismatchException ex) {
		LOGGER.warn("Payment amount mismatch: {}", ex.getMessage());
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
		problem.setType(AMOUNT_MISMATCH_TYPE);
		problem.setTitle("Payment amount does not match the order");
		problem.setProperty("orderId", ex.getOrderId());
		return problem;
	}

	@ExceptionHandler(OrderNotFoundException.class)
	ProblemDetail handleOrderNotFound(OrderNotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setType(ORDER_NOT_FOUND_TYPE);
		problem.setTitle("Order not found");
		problem.setProperty("orderId", ex.getOrderId());
		return problem;
	}

}

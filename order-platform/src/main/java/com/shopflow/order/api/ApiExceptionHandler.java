package com.shopflow.order.api;

import java.net.URI;
import java.util.List;

import org.jspecify.annotations.Nullable;
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

import com.shopflow.order.domain.InvalidSortException;
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

	private static final URI ORDER_NOT_FOUND_TYPE =
			URI.create("https://example.com/problems/order-not-found");

	private static final URI ORDER_NOT_MODIFIABLE_TYPE =
			URI.create("https://example.com/problems/order-not-modifiable");

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

	@ExceptionHandler(OrderNotFoundException.class)
	ProblemDetail handleOrderNotFound(OrderNotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setType(ORDER_NOT_FOUND_TYPE);
		problem.setTitle("Order not found");
		problem.setProperty("orderId", ex.getOrderId());
		return problem;
	}

}

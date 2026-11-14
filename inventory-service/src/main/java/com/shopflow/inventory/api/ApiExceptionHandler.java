package com.shopflow.inventory.api;

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

import com.shopflow.inventory.domain.InsufficientStockException;
import com.shopflow.inventory.domain.ReservationNotFoundException;
import com.shopflow.inventory.domain.ReservationReleasedException;
import com.shopflow.inventory.domain.ReservationShippedException;
import com.shopflow.inventory.domain.UnknownSkuException;

/**
 * Turns exceptions into RFC 9457 problem details. The base class already maps Spring MVC's own
 * exceptions (malformed JSON, missing parameters, ...); validation failures get a field list.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final URI INSUFFICIENT_STOCK_TYPE = URI.create("https://example.com/problems/insufficient-stock");

	private static final URI RESERVATION_SHIPPED_TYPE = URI.create("https://example.com/problems/reservation-shipped");

	private static final URI RESERVATION_NOT_FOUND_TYPE = URI.create("https://example.com/problems/reservation-not-found");

	private static final URI UNKNOWN_SKU_TYPE = URI.create("https://example.com/problems/unknown-sku");

	/** Adds a per-field {@code errors} list to the standard 400 problem detail. */
	@Override
	protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
				.toList();
		ex.getBody().setProperty("errors", errors);
		return super.handleMethodArgumentNotValid(ex, headers, status, request);
	}

	public record FieldViolation(String field, @Nullable String message) {
	}

	@ExceptionHandler(InsufficientStockException.class)
	ProblemDetail handleInsufficientStock(InsufficientStockException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setType(INSUFFICIENT_STOCK_TYPE);
		problem.setTitle("Insufficient stock");
		problem.setProperty("sku", ex.getSku());
		return problem;
	}

	/** Releasing after shipping is refused: the goods are gone, giving the stock back would invent units. */
	@ExceptionHandler(ReservationShippedException.class)
	ProblemDetail handleReservationShipped(ReservationShippedException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setType(RESERVATION_SHIPPED_TYPE);
		problem.setTitle("Reservation already shipped");
		problem.setProperty("reservationId", ex.getReservationId());
		return problem;
	}

	@ExceptionHandler(ReservationReleasedException.class)
	ProblemDetail handleReservationReleased(ReservationReleasedException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setType(URI.create("https://example.com/problems/reservation-released"));
		problem.setTitle("Reservation already released");
		problem.setProperty("reservationId", ex.getReservationId());
		return problem;
	}

	@ExceptionHandler(ReservationNotFoundException.class)
	ProblemDetail handleReservationNotFound(ReservationNotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setType(RESERVATION_NOT_FOUND_TYPE);
		problem.setTitle("Reservation not found");
		problem.setProperty("reservationId", ex.getReservationId());
		return problem;
	}

	@ExceptionHandler(UnknownSkuException.class)
	ProblemDetail handleUnknownSku(UnknownSkuException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setType(UNKNOWN_SKU_TYPE);
		problem.setTitle("Unknown SKU");
		problem.setProperty("sku", ex.getSku());
		return problem;
	}

}

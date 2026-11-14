package com.shopflow.inventory.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.shopflow.inventory.domain.StockLine;
import com.shopflow.inventory.domain.StockService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * The stock API order-platform calls (see its {@code InventoryClient}). Needs the realm role
 * {@code inventory} (see {@code SecurityConfig}).
 */
@RestController
@RequestMapping("/api/stock")
public class StockController {

	private final StockService stock;

	public StockController(StockService stock) {
		this.stock = stock;
	}

	/** Units of one SKU that can still be reserved. 404 for a SKU with no stock row. */
	@GetMapping("/{sku}")
	StockLevel get(@PathVariable String sku) {
		return new StockLevel(sku, this.stock.available(sku));
	}

	/**
	 * Reserves every line or none (409). Repeating a request with the same {@code reservationId}
	 * answers 200 again and takes no further stock.
	 */
	@PostMapping("/reservations")
	ReservationResponse reserve(@Valid @RequestBody ReservationRequest request) {
		List<StockLine> lines = request.lines().stream().map(l -> new StockLine(l.sku(), l.quantity())).toList();
		this.stock.reserve(request.reservationId(), lines);
		return new ReservationResponse(request.reservationId());
	}

	/** 204 when released, 404 when unknown, 409 when the reservation has shipped. */
	@DeleteMapping("/reservations/{reservationId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void release(@PathVariable String reservationId) {
		this.stock.release(reservationId);
	}

	public record StockLevel(String sku, int available) {
	}

	public record ReservationResponse(String reservationId) {
	}

	public record ReservationRequest(@NotBlank @Size(max = 100) String reservationId,
			@NotEmpty List<@Valid Line> lines) {
	}

	public record Line(@NotBlank @Size(max = 64) String sku, @Min(1) @Max(1_000_000) int quantity) {
	}

}

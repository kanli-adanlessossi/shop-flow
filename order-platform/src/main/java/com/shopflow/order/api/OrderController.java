package com.shopflow.order.api;

import java.net.URI;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.shopflow.order.domain.OrderDetails;
import com.shopflow.order.domain.OrderService;
import com.shopflow.order.domain.OrderStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Orders API. Every operation exists in version 1 and version 2, which differ only in the
 * representation they return: version 2 adds the status and totals. Version 1 is deprecated.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

	private static final String DEFAULT_SORT = "id";

	// Access rules (SpEL for @PreAuthorize). An admin may do anything; everyone else only on their own
	// orders (see OrderAccess), and a new or replaced order must name the caller as its customer.
	private static final String ADMIN = "hasRole('ADMIN')";

	private static final String OWNER = "@orderAccess.isOwner(#id, authentication)";

	private static final String CUSTOMER_IS_CALLER = "#request.customerId() == authentication.name";

	private static final String NOT_A_CANCEL = "#request.status() != T(com.shopflow.order.domain.OrderStatus).CANCELLED";

	private static final String CREATE_RULE = ADMIN + " or " + CUSTOMER_IS_CALLER;

	private static final String OWNER_RULE = ADMIN + " or " + OWNER;

	private static final String REPLACE_RULE = ADMIN + " or (" + OWNER + " and " + CUSTOMER_IS_CALLER + ")";

	private static final String STATUS_RULE = ADMIN + " or (" + OWNER + " and " + NOT_A_CANCEL + ")";

	private final OrderService orders;

	public OrderController(OrderService orders) {
		this.orders = orders;
	}

	// ---- create -------------------------------------------------------------------------

	@PreAuthorize(CREATE_RULE)
	@PostMapping(version = "1")
	public ResponseEntity<OrderV1> createV1(@Valid @RequestBody OrderRequest request) {
		OrderDetails created = this.orders.create(request.toDraft());
		return ResponseEntity.created(location(created.id())).body(OrderV1.from(created));
	}

	@PreAuthorize(CREATE_RULE)
	@PostMapping(version = "2")
	public ResponseEntity<OrderV2> createV2(@Valid @RequestBody OrderRequest request) {
		OrderDetails created = this.orders.create(request.toDraft());
		return ResponseEntity.created(location(created.id())).body(OrderV2.from(created));
	}

	// ---- read ---------------------------------------------------------------------------

	@PreAuthorize(OWNER_RULE)
	@GetMapping(path = "/{id}", version = "1")
	public OrderV1 getV1(@PathVariable long id) {
		return OrderV1.from(this.orders.findById(id));
	}

	@PreAuthorize(OWNER_RULE)
	@GetMapping(path = "/{id}", version = "2")
	public OrderV2 getV2(@PathVariable long id) {
		return OrderV2.from(this.orders.findById(id));
	}

	/** Newest first by default. Version 1 has no status, so it cannot be filtered by it. */
	@GetMapping(version = "1")
	public Page<OrderV1.Item> listV1(
			@PageableDefault(size = 20, sort = DEFAULT_SORT, direction = Sort.Direction.DESC) Pageable pageable) {
		return this.orders.search(null, pageable).map(OrderV1.Item::from);
	}

	@GetMapping(version = "2")
	public Page<OrderV2.Item> listV2(
			@RequestParam(required = false) @Nullable OrderStatus status,
			@PageableDefault(size = 20, sort = DEFAULT_SORT, direction = Sort.Direction.DESC) Pageable pageable) {
		return this.orders.search(status, pageable).map(OrderV2.Item::from);
	}

	// ---- replace ------------------------------------------------------------------------

	@PreAuthorize(REPLACE_RULE)
	@PutMapping(path = "/{id}", version = "1")
	public OrderV1 replaceV1(@PathVariable long id, @Valid @RequestBody OrderRequest request) {
		return OrderV1.from(this.orders.replace(id, request.toDraft()));
	}

	@PreAuthorize(REPLACE_RULE)
	@PutMapping(path = "/{id}", version = "2")
	public OrderV2 replaceV2(@PathVariable long id, @Valid @RequestBody OrderRequest request) {
		return OrderV2.from(this.orders.replace(id, request.toDraft()));
	}

	// ---- status (version 2 only: version 1 has no status) -------------------------------

	/**
	 * The owner may move an order to another status, but only an admin may cancel one (answer 403
	 * otherwise). The checks are on this HTTP entry point, not on {@code OrderService}: the scheduled
	 * cleanup of unpaid orders cancels through the service without a logged-in user.
	 */
	@PreAuthorize(STATUS_RULE)
	@PatchMapping(path = "/{id}/status", version = "2")
	public OrderV2 updateStatus(@PathVariable long id, @Valid @RequestBody UpdateStatusRequest request) {
		return OrderV2.from(this.orders.updateStatus(id, request.status()));
	}

	public record UpdateStatusRequest(@NotNull OrderStatus status) {
	}

	// ---- delete -------------------------------------------------------------------------

	/** Same behaviour in every version, so one handler covers version 1 and above. */
	@PreAuthorize(OWNER_RULE)
	@DeleteMapping(path = "/{id}", version = "1+")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable long id) {
		this.orders.delete(id);
	}

	private static URI location(Long id) {
		return ServletUriComponentsBuilder.fromCurrentRequestUri().path("/{id}").buildAndExpand(id).toUri();
	}

}

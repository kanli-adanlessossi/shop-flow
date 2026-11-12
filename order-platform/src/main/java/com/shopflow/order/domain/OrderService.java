package com.shopflow.order.domain;

import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

	static final String CACHE = "orders";

	private final OrderRepository orders;

	public OrderService(OrderRepository orders) {
		this.orders = orders;
	}

	@Transactional
	public OrderDetails create(OrderDraft draft) {
		Order order = new Order(draft.customerId(), draft.discountCode());
		toLines(draft).forEach(order::addLine);
		return OrderDetails.from(this.orders.save(order));
	}

	@Cacheable(cacheNames = CACHE, key = "#id")
	@Transactional(readOnly = true)
	public OrderDetails findById(long id) {
		return OrderDetails.from(load(id));
	}

	/** The columns a list can be sorted by. Hand-written queries do not validate this themselves. */
	private static final Set<String> SORTABLE = Set.of("id", "customerId", "status", "createdAt", "total");

	@Transactional(readOnly = true)
	public Page<OrderSummary> search(@Nullable OrderStatus status, Pageable pageable) {
		for (Sort.Order order : pageable.getSort()) {
			if (!SORTABLE.contains(order.getProperty())) {
				throw new InvalidSortException(order.getProperty());
			}
		}
		return (status != null)
				? this.orders.findSummariesByStatus(status, pageable)
				: this.orders.findSummaries(pageable);
	}

	/** Replaces the content of a pending order. */
	@CacheEvict(cacheNames = CACHE, key = "#id")
	@Transactional
	public OrderDetails replace(long id, OrderDraft draft) {
		Order order = load(id);
		if (!order.canBeModified()) {
			throw new OrderNotModifiableException(String.valueOf(id), order.getStatus(), "modified");
		}
		order.replaceContent(draft.customerId(), draft.discountCode(), toLines(draft));
		return OrderDetails.from(order);
	}

	/** Changes the status and drops the cached copy so the next read sees it. */
	@CacheEvict(cacheNames = CACHE, key = "#id")
	@Transactional
	public OrderDetails updateStatus(long id, OrderStatus status) {
		Order order = load(id);
		order.setStatus(status);
		return OrderDetails.from(order);
	}

	/** Deletes a pending or cancelled order together with its lines. */
	@CacheEvict(cacheNames = CACHE, key = "#id")
	@Transactional
	public void delete(long id) {
		Order order = load(id);
		if (!order.canBeDeleted()) {
			throw new OrderNotModifiableException(String.valueOf(id), order.getStatus(), "deleted");
		}
		this.orders.delete(order);
	}

	private Order load(long id) {
		return this.orders.findById(id)
				.orElseThrow(() -> new OrderNotFoundException(String.valueOf(id)));
	}

	private static List<OrderLine> toLines(OrderDraft draft) {
		return draft.lines().stream()
				.map(line -> new OrderLine(line.sku(), line.quantity(), line.unitPrice()))
				.toList();
	}

}

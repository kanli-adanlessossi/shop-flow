package com.shopflow.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import com.shopflow.order.events.OrderCancelled;
import com.shopflow.order.events.OrderConfirmed;
import com.shopflow.order.events.OrderPlaced;
import com.shopflow.order.events.OutboxEvents;
import com.shopflow.order.events.ProcessedEvents;

@Service
public class OrderService {

	static final String CACHE = "orders";

	private final OrderRepository orders;

	private final StockReservations stock;

	private final TransactionOperations tx;

	private final ReservationRelease release;

	private final OutboxEvents events;

	private final ProcessedEvents processed;

	public OrderService(OrderRepository orders, StockReservations stock, TransactionOperations tx,
			ReservationRelease release, OutboxEvents events, ProcessedEvents processed) {
		this.orders = orders;
		this.stock = stock;
		this.tx = tx;
		this.release = release;
		this.events = events;
		this.processed = processed;
	}

	/**
	 * Reserves the stock first, then saves the order as {@link OrderStatus#PENDING_PAYMENT}. The
	 * remote call runs outside any transaction so a slow inventory never holds a database
	 * connection. Nothing is saved when the reservation fails ({@link InsufficientStockException}
	 * or {@link InventoryUnavailableException}).
	 *
	 * <p>The order does not exist yet, so the reservation is keyed by a fresh UUID that the order
	 * then stores. If something fails after stock may have been taken, it is released in the
	 * background:
	 * <ul>
	 * <li>the reservation call could not be completed, e.g. a timeout: inventory may have reserved
	 * anyway, so release is attempted;</li>
	 * <li>the order could not be saved.</li>
	 * </ul>
	 */
	public OrderDetails create(OrderDraft draft) {
		String reservationId = UUID.randomUUID().toString();
		try {
			this.stock.reserve(reservationId, draft.lines());
		}
		catch (InventoryUnavailableException ex) {
			this.release.releaseInBackground(reservationId);
			throw ex;
		}
		try {
			return Objects.requireNonNull(this.tx.execute(status -> {
				Order order = new Order(draft.customerId(), draft.discountCode(), reservationId);
				toLines(draft).forEach(order::addLine);
				Order saved = this.orders.save(order);
				// Same transaction as the order: the event exists if and only if the order does.
				this.events.add(new OrderPlaced(Objects.requireNonNull(saved.getId()), saved.getCustomerId(), total(saved)));
				return OrderDetails.from(saved);
			}));
		}
		catch (RuntimeException ex) {
			this.release.releaseInBackground(reservationId);
			throw ex;
		}
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

	/**
	 * Replaces the content of an order that is waiting for payment. The stock reserved at creation
	 * is not adjusted, so changed quantities or SKUs are not reflected at inventory-service.
	 */
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
		OrderStatus before = order.getStatus();
		order.setStatus(status);
		if (status == OrderStatus.CANCELLED && before != OrderStatus.CANCELLED) {
			this.events.add(new OrderCancelled(id, order.getCustomerId(), "cancelled on request"));
		}
		return OrderDetails.from(order);
	}

	/** Deletes an order that is waiting for payment, or a cancelled one, together with its lines. */
	@CacheEvict(cacheNames = CACHE, key = "#id")
	@Transactional
	public void delete(long id) {
		Order order = load(id);
		if (!order.canBeDeleted()) {
			throw new OrderNotModifiableException(String.valueOf(id), order.getStatus(), "deleted");
		}
		this.orders.delete(order);
	}

	/** The oldest unpaid (PENDING_PAYMENT) orders created before the cutoff, at most {@code limit} of them. */
	@Transactional(readOnly = true)
	public List<Long> findUnpaidCreatedBefore(Instant cutoff, int limit) {
		return this.orders.findIdsByStatusCreatedBefore(OrderStatus.PENDING_PAYMENT, cutoff, Limit.of(limit));
	}

	/**
	 * The reservation key of the order if it is still waiting for payment and older than the cutoff
	 * (it may have been paid since), otherwise empty.
	 */
	@Transactional(readOnly = true)
	public Optional<String> unpaidReservationId(long id, Instant cutoff) {
		return this.orders.findById(id)
				.filter(order -> order.getStatus() == OrderStatus.PENDING_PAYMENT
						&& order.getCreatedAt().isBefore(cutoff))
				.map(Order::getReservationId);
	}

	/**
	 * Cancels the order if it is still PENDING_PAYMENT and older than the cutoff. The check and the change
	 * happen in one transaction, so an order paid in the meantime is left alone.
	 *
	 * @return the customer id of the cancelled order, or empty if the order was left alone
	 */
	@CacheEvict(cacheNames = CACHE, key = "#id")
	@Transactional
	public Optional<String> cancelIfStillUnpaid(long id, Instant cutoff) {
		return this.orders.findById(id)
				.filter(order -> order.getStatus() == OrderStatus.PENDING_PAYMENT
						&& order.getCreatedAt().isBefore(cutoff))
				.map(order -> {
					order.setStatus(OrderStatus.CANCELLED);
					this.events.add(new OrderCancelled(id, order.getCustomerId(), "unpaid"));
					return order.getCustomerId();
				});
	}

	/** Names the payment provider's webhook in the inbox, so its event ids do not mix with other consumers'. */
	static final String PAYMENT_WEBHOOK = "payment-webhook";

	/**
	 * The payment provider says an order was paid: confirms it.
	 *
	 * <p>Providers deliver webhooks at least once and not necessarily in order, so this is safe to call
	 * again with the same input:
	 * <ul>
	 * <li>the provider's {@code eventId} is recorded in the inbox in the same transaction as the status
	 * change, so a repeat is a {@link PaymentOutcome#DUPLICATE} that changes nothing;</li>
	 * <li>an order that is already CONFIRMED (or shipped) stays as it is
	 * ({@link PaymentOutcome#ALREADY_CONFIRMED});</li>
	 * <li>a failure rolls the inbox row back with everything else, so the provider's retry is handled
	 * again.</li>
	 * </ul>
	 *
	 * @throws OrderNotFoundException there is no such order
	 * @throws LatePaymentException the order was cancelled in the meantime; the customer has paid and needs a refund
	 * @throws PaymentAmountMismatchException the amount paid is not the order's total
	 */
	@CacheEvict(cacheNames = CACHE, key = "#id")
	@Transactional
	public PaymentOutcome confirmPayment(long id, BigDecimal amountPaid, String providerEventId) {
		Order order = load(id);
		if (!this.processed.markProcessed(PAYMENT_WEBHOOK, providerEventId)) {
			return PaymentOutcome.DUPLICATE;
		}
		switch (order.getStatus()) {
			case CONFIRMED, SHIPPED:
				return PaymentOutcome.ALREADY_CONFIRMED;
			case CANCELLED:
				throw new LatePaymentException(String.valueOf(id));
			case PENDING_PAYMENT:
				BigDecimal total = total(order);
				if (total.compareTo(amountPaid) != 0) {
					throw new PaymentAmountMismatchException(String.valueOf(id), total, amountPaid);
				}
				order.setStatus(OrderStatus.CONFIRMED);
				this.events.add(new OrderConfirmed(id, order.getCustomerId(), total));
				return PaymentOutcome.CONFIRMED;
			default:
				throw new IllegalStateException("Unhandled status " + order.getStatus());
		}
	}

	private static BigDecimal total(Order order) {
		return order.getLines().stream()
				.map(line -> line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity())))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
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

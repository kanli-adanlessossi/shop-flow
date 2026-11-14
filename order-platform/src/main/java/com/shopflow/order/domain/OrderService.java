package com.shopflow.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Limit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

	private final SkuPriceRepository prices;

	public OrderService(OrderRepository orders, StockReservations stock, TransactionOperations tx,
			ReservationRelease release, OutboxEvents events, ProcessedEvents processed, SkuPriceRepository prices) {
		this.orders = orders;
		this.stock = stock;
		this.tx = tx;
		this.release = release;
		this.events = events;
		this.processed = processed;
		this.prices = prices;
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
		// Priced first: an unknown SKU is rejected before any stock is taken.
		List<OrderLine> lines = toLines(draft);
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
				lines.forEach(order::addLine);
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
	public Page<OrderSummary> search(@Nullable String customerId, @Nullable OrderStatus status, Pageable pageable) {
		for (Sort.Order order : pageable.getSort()) {
			if (!SORTABLE.contains(order.getProperty())) {
				throw new InvalidSortException(order.getProperty());
			}
		}
		if (customerId == null) {
			return (status != null)
					? this.orders.findSummariesByStatus(status, pageable)
					: this.orders.findSummaries(pageable);
		}
		return (status != null)
				? this.orders.findSummariesByCustomerIdAndStatus(customerId, status, pageable)
				: this.orders.findSummariesByCustomerId(customerId, pageable);
	}

	/**
	 * Replaces the content of an order that is waiting for payment. The lines are priced again from the
	 * price list.
	 *
	 * <p>The stock reservation follows the lines. When the SKUs or quantities change, the new lines are
	 * reserved under a fresh key first (outside any transaction, like {@link #create}); only then does the
	 * order switch to the new key, and the old reservation is released once that commit succeeded. Reserving
	 * before releasing means the order is never under-reserved, at the price that the new lines must fit
	 * next to the old ones for a moment: near an empty shelf a change can be refused with
	 * {@link InsufficientStockException} although the net change would fit. A refused or failed change
	 * leaves the order and its old reservation as they were. When only the customer or discount code
	 * changes, inventory is not called.
	 */
	@CacheEvict(cacheNames = CACHE, key = "#id")
	public OrderDetails replace(long id, OrderDraft draft) {
		List<OrderLine> lines = toLines(draft);
		Map<String, Integer> wanted = quantities(draft.lines());
		// Same stock as before: change the order in one transaction, without calling inventory.
		String[] reservedUnder = new String[1];
		Optional<OrderDetails> unchanged = Objects.requireNonNull(this.tx.execute(status -> {
			Order order = load(id);
			assertModifiable(order);
			if (!quantities(order.getLines().stream()
					.map(line -> new OrderDraft.Line(line.getSku(), line.getQuantity())).toList()).equals(wanted)) {
				reservedUnder[0] = order.getReservationId();
				return Optional.<OrderDetails>empty();
			}
			order.replaceContent(draft.customerId(), draft.discountCode(), lines);
			return Optional.of(OrderDetails.from(order));
		}));
		if (unchanged.isPresent()) {
			return unchanged.get();
		}
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
				Order order = load(id);
				assertModifiable(order);
				String previous = order.getReservationId();
				if (!previous.equals(reservedUnder[0])) {
					// Another replace moved the reservation while this one was reserving: answer 409.
					throw new ObjectOptimisticLockingFailureException(Order.class, id);
				}
				order.replaceContent(draft.customerId(), draft.discountCode(), lines);
				order.setReservationId(reservationId);
				releaseAfterCommit(previous);
				return OrderDetails.from(order);
			}));
		}
		catch (RuntimeException ex) {
			this.release.releaseInBackground(reservationId);
			throw ex;
		}
	}

	private static void assertModifiable(Order order) {
		if (!order.canBeModified()) {
			throw new OrderNotModifiableException(String.valueOf(order.getId()), order.getStatus(), "modified");
		}
	}

	/** Total quantity per SKU, so the order of lines and a SKU split over two lines do not matter. */
	private static Map<String, Integer> quantities(List<OrderDraft.Line> lines) {
		return lines.stream().collect(Collectors.toMap(OrderDraft.Line::sku, OrderDraft.Line::quantity, Integer::sum));
	}

	/**
	 * Changes the status and drops the cached copy so the next read sees it. Only PENDING_PAYMENT to
	 * CANCELLED (which gives the reserved stock back) and CONFIRMED to SHIPPED are allowed; an order
	 * becomes CONFIRMED only through {@link #confirmPayment}. Asking for the current status changes nothing.
	 *
	 * @throws IllegalStatusTransitionException any other change
	 */
	@CacheEvict(cacheNames = CACHE, key = "#id")
	@Transactional
	public OrderDetails updateStatus(long id, OrderStatus status) {
		Order order = load(id);
		OrderStatus before = order.getStatus();
		if (before == status) {
			return OrderDetails.from(order);
		}
		boolean allowed = (before == OrderStatus.PENDING_PAYMENT && status == OrderStatus.CANCELLED)
				|| (before == OrderStatus.CONFIRMED && status == OrderStatus.SHIPPED);
		if (!allowed) {
			throw new IllegalStatusTransitionException(String.valueOf(id), before, status);
		}
		order.setStatus(status);
		if (status == OrderStatus.CANCELLED) {
			// Only PENDING_PAYMENT orders get here (see the transition check above), and only a customer or an
			// admin acting for them cancels an unpaid order, so one reason fits. "unpaid" is the cleanup job's.
			this.events.add(new OrderCancelled(id, order.getCustomerId(), "cancelled on request"));
			releaseAfterCommit(order.getReservationId());
		}
		return OrderDetails.from(order);
	}

	/**
	 * Deletes an order that is waiting for payment, or a cancelled one, together with its lines, and gives
	 * its reserved stock back (releasing is idempotent, so this is harmless for a cancelled order).
	 */
	@CacheEvict(cacheNames = CACHE, key = "#id")
	@Transactional
	public void delete(long id) {
		Order order = load(id);
		if (!order.canBeDeleted()) {
			throw new OrderNotModifiableException(String.valueOf(id), order.getStatus(), "deleted");
		}
		releaseAfterCommit(order.getReservationId());
		this.orders.delete(order);
	}

	/** The oldest unpaid (PENDING_PAYMENT) orders created before the cutoff, at most {@code limit} of them. */
	@Transactional(readOnly = true)
	public List<Long> findUnpaidCreatedBefore(Instant cutoff, int limit) {
		return this.orders.findIdsByStatusCreatedBefore(OrderStatus.PENDING_PAYMENT, cutoff, Limit.of(limit));
	}

	/**
	 * Cancels the order if it is still PENDING_PAYMENT and older than the cutoff, and marks its stock release
	 * as pending. The check and the change happen in one transaction, so an order paid in the meantime is left
	 * alone. The caller releases the stock after this commit and then calls {@link #stockReleased}.
	 *
	 * @return the reservation key to release, or empty if the order was left alone
	 */
	@CacheEvict(cacheNames = CACHE, key = "#id")
	@Transactional
	public Optional<String> cancelIfStillUnpaid(long id, Instant cutoff) {
		return this.orders.findById(id)
		.filter(order -> order.getStatus() == OrderStatus.PENDING_PAYMENT
		&& order.getCreatedAt().isBefore(cutoff))
		.map(order -> {
			order.setStatus(OrderStatus.CANCELLED);
			order.setStockReleasePending(true);
			this.events.add(new OrderCancelled(id, order.getCustomerId(), "unpaid"));
			return order.getReservationId();
		});
	}

	/** Orders the cleanup job cancelled whose stock release is not confirmed yet, oldest first, at most {@code limit}. */
	@Transactional(readOnly = true)
	public List<Long> findStockReleasePending(int limit) {
		return this.orders.findIdsWithStockReleasePending(Limit.of(limit));
	}

	/** The reservation key of a cancelled order whose stock release is still pending, otherwise empty. */
	@Transactional(readOnly = true)
	public Optional<String> pendingReservationId(long id) {
		return this.orders.findById(id).filter(Order::isStockReleasePending).map(Order::getReservationId);
	}

	/** Inventory confirmed the release: the cleanup job no longer retries this order. */
	@Transactional
	public void stockReleased(long id) {
		this.orders.clearStockReleasePending(id);
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

	/** Builds the lines with the unit prices from the price list; the caller never sets a price. */
	private List<OrderLine> toLines(OrderDraft draft) {
		Map<String, BigDecimal> priceBySku = this.prices
				.findAllById(draft.lines().stream().map(OrderDraft.Line::sku).distinct().toList()).stream()
				.collect(Collectors.toMap(SkuPrice::getSku, SkuPrice::getUnitPrice));
		return draft.lines().stream()
				.map(line -> new OrderLine(line.sku(), line.quantity(), Optional.ofNullable(priceBySku.get(line.sku()))
						.orElseThrow(() -> new UnknownSkuException(line.sku()))))
				.toList();
	}

	/** Gives the stock back once the change is committed, so a rolled-back change does not release it. */
	private void releaseAfterCommit(String reservationId) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					OrderService.this.release.releaseInBackground(reservationId);
				}
			});
		}
		else {
			this.release.releaseInBackground(reservationId);
		}
	}

}

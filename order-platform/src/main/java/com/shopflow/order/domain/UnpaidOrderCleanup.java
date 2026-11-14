package com.shopflow.order.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.shopflow.order.ShopFlowProperties;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
	 * Cancels orders that were never paid within {@code shopflow.cancel-after} and gives their stock
	 * back. Runs on {@code shopflow.cancel-cron} (default: every 5 minutes, UTC).
	 *
	 * <p>Per order: cancel it first (re-checking in the same transaction that it is still unpaid), then
	 * release the stock. Cancelling first closes the window in which a payment could confirm an order whose
	 * reservation was already gone: once the cancel has committed, a late payment is refused as such. The cancel
	 * also sets {@code stockReleasePending}; the flag is cleared when inventory confirms the release. If the
	 * release fails, the order stays cancelled with the flag set, and every run retries pending releases
	 * before it looks at new unpaid orders (release is safe to repeat).
	 *
	 * <p>Batches of {@value #BATCH_SIZE} per run for each of the two steps; anything left is picked up by
	 * the next run. With several instances, ShedLock ({@code @SchedulerLock}) lets only one run at a
	 * time; the steps are idempotent anyway, so a run that overlaps after the lock expired is harmless
	 * but wasteful. {@code lockAtMostFor} must stay well above the longest realistic run: a batch where
	 * inventory is slow (see the retry settings) can take minutes.
	 */
@Component
public class UnpaidOrderCleanup {

	static final int BATCH_SIZE = 200;

	private static final Logger LOGGER = LoggerFactory.getLogger(UnpaidOrderCleanup.class);

	private final OrderService orders;

	private final StockReservations stock;

	private final ShopFlowProperties properties;

	private final Clock clock;

	public UnpaidOrderCleanup(OrderService orders, StockReservations stock, ShopFlowProperties properties,
			Clock clock) {
		this.orders = orders;
		this.stock = stock;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(cron = "${shopflow.cancel-cron:0 */5 * * * *}", zone = "UTC")
	@SchedulerLock(name = "cancelUnpaid", lockAtMostFor = "5m")
	public void cancelUnpaidOrders() {
		Instant cutoff = this.clock.instant().minus(this.properties.cancelAfter());
		// First finish the releases that earlier runs could not complete.
		int released = 0;
		for (long id : this.orders.findStockReleasePending(BATCH_SIZE)) {
			try {
				Optional<String> key = this.orders.pendingReservationId(id);
				if (key.isPresent()) {
					release(id, key.get());
					released++;
				}
			}
			catch (InventoryUnavailableException ex) {
				LOGGER.warn("Stopping unpaid-order cleanup: inventory unavailable while retrying releases ({})",
						ex.getMessage());
				return;
			}
			catch (RuntimeException ex) {
				LOGGER.error("Could not release the stock of cancelled order {}", id, ex);
			}
		}
		List<Long> candidates = this.orders.findUnpaidCreatedBefore(cutoff, BATCH_SIZE);
		int cancelled = 0;
		int skipped = 0;
		for (long id : candidates) {
			// The customer is told through the order.cancelled event that this step records in the outbox.
			Optional<String> reservationId;
			try {
				reservationId = this.orders.cancelIfStillUnpaid(id, cutoff);
			}
			catch (RuntimeException ex) {
				skipped++;
				LOGGER.error("Could not cancel unpaid order {}", id, ex);
				continue;
			}
			if (reservationId.isEmpty()) {
				skipped++;
				continue;
			}
			// From here on the order is cancelled, whatever the release does.
			cancelled++;
			try {
				release(id, reservationId.get());
			}
			catch (InventoryUnavailableException ex) {
				// Inventory is down: every remaining order would fail the same way. The orders cancelled so
				// far keep their pending flag and are retried first on the next run.
				LOGGER.warn("Stopping unpaid-order cleanup: inventory unavailable after {} cancelled ({})", cancelled,
						ex.getMessage());
				return;
			}
			catch (RuntimeException ex) {
				LOGGER.error("Order {} is cancelled but its stock release failed; the next run retries it", id, ex);
			}
		}
		if (!candidates.isEmpty() || released > 0) {
			LOGGER.info("Unpaid-order cleanup: {} cancelled, {} skipped (older than {}), {} earlier releases completed",
					cancelled, skipped, this.properties.cancelAfter(), released);
		}
	}

	/** Gives the stock back and clears the pending flag; a failure leaves the flag for the next run. */
	private void release(long id, String reservationId) {
		this.stock.release(reservationId);
		this.orders.stockReleased(id);
	}
}

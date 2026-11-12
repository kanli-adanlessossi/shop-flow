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
 * <p>Per order: re-check it is still unpaid, release the stock, then cancel it. Releasing first
 * means a failed release leaves the order PENDING_PAYMENT, so the next run tries again (release is safe to
 * repeat). The cost is a short window in which an order paid between the release and the cancel
 * would lose its reservation; the cancel step re-checks the status and leaves a paid order alone.
 * Closing that window completely would need an extra status or a "stock released" flag.
 *
 * <p>Orders are handled in batches of {@value #BATCH_SIZE} per run; anything left is picked up by
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

	private final CancellationNotifier notifier;

	public UnpaidOrderCleanup(OrderService orders, StockReservations stock, CancellationNotifier notifier,
			ShopFlowProperties properties, Clock clock) {
		this.orders = orders;
		this.stock = stock;
		this.notifier = notifier;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(cron = "${shopflow.cancel-cron:0 */5 * * * *}", zone = "UTC")
	@SchedulerLock(name = "cancelUnpaid", lockAtMostFor = "5m")
	public void cancelUnpaidOrders() {
		Instant cutoff = this.clock.instant().minus(this.properties.cancelAfter());
		List<Long> candidates = this.orders.findUnpaidCreatedBefore(cutoff, BATCH_SIZE);
		int cancelled = 0;
		int skipped = 0;
		for (long id : candidates) {
			try {
				if (cancelOne(id, cutoff)) {
					cancelled++;
				}
				else {
					skipped++;
				}
			}
			catch (InventoryUnavailableException ex) {
				// Inventory is down: every remaining order would fail the same way. Retry next run.
				LOGGER.warn("Stopping unpaid-order cleanup: inventory unavailable after {} cancelled ({})", cancelled,
						ex.getMessage());
				return;
			}
			catch (RuntimeException ex) {
				skipped++;
				LOGGER.error("Could not cancel unpaid order {}", id, ex);
			}
		}
		if (!candidates.isEmpty()) {
			LOGGER.info("Unpaid-order cleanup: {} cancelled, {} skipped (older than {})", cancelled, skipped,
					this.properties.cancelAfter());
		}
	}

	private boolean cancelOne(long id, Instant cutoff) {
		Optional<String> reservationId = this.orders.unpaidReservationId(id, cutoff);
		if (reservationId.isEmpty()) {
			return false;
		}
		this.stock.release(reservationId.get());
		return this.orders.cancelIfStillUnpaid(id, cutoff)
				.map(customerId -> {
					this.notifier.sendCancellationNotice(id, customerId);
					return true;
				})
				.orElse(false);
	}

}

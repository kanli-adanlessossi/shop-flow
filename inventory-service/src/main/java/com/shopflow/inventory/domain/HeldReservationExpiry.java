package com.shopflow.inventory.domain;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.shopflow.inventory.InventoryProperties;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Gives back the stock of reservations that were never confirmed. order-platform releases the
 * reservation itself when it cancels an unpaid order; this job covers the case where that release never
 * arrived (order-platform down, a lost call). Only HELD reservations older than
 * {@code shopflow.inventory.hold-ttl} are touched: CONFIRMED and SHIPPED ones never expire.
 *
 * <p>Runs on one instance at a time (ShedLock). Each reservation is released in its own transaction by
 * {@link StockService#releaseIfStillHeld}, which re-checks the status under a row lock, so a
 * confirmation that arrives while the job runs wins.
 */
@Component
public class HeldReservationExpiry {

	private static final Logger LOGGER = LoggerFactory.getLogger(HeldReservationExpiry.class);

	private final ReservationRepository reservations;

	private final StockService stock;

	private final InventoryProperties properties;

	private final Clock clock;

	public HeldReservationExpiry(ReservationRepository reservations, StockService stock, InventoryProperties properties,
			Clock clock) {
		this.reservations = reservations;
		this.stock = stock;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(cron = "${shopflow.inventory.expiry-cron:0 */5 * * * *}", zone = "UTC")
	@SchedulerLock(name = "expireHeldReservations", lockAtMostFor = "10m")
	public void expire() {
		Instant cutoff = this.clock.instant().minus(this.properties.holdTtl());
		int released = 0;
		for (String id : this.reservations.findHeldIdsCreatedBefore(cutoff)) {
			try {
				if (this.stock.releaseIfStillHeld(id, cutoff)) {
					released++;
				}
			}
			catch (RuntimeException ex) {
				// One failing reservation must not block the others; the next run tries it again.
				LOGGER.warn("Could not release expired reservation {}", id, ex);
			}
		}
		Instant markerCutoff = this.clock.instant().minus(this.properties.processedEventsRetention());
		int purged = this.reservations.deleteReleasedBefore(markerCutoff);
		if (purged > 0) {
			LOGGER.info("Deleted {} released-reservation markers older than {}", purged, this.properties.processedEventsRetention());
		}
		if (released > 0) {
			LOGGER.info("Released {} reservations held longer than {}", released, this.properties.holdTtl());
		}
	}

}

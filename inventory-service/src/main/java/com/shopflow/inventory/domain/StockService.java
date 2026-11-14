package com.shopflow.inventory.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stock rules. Safe with several threads and several instances because nothing is kept in the
 * process: every decision is a conditional UPDATE or INSERT in PostgreSQL, inside one transaction.
 *
 * <p>Lock order: a reservation row first, then stock rows in SKU order. Two reservations that name
 * the same SKUs in opposite order therefore cannot wait for each other in a circle.
 */
@Service
public class StockService {

	private static final Logger LOGGER = LoggerFactory.getLogger(StockService.class);

	private final StockRepository stock;

	private final ReservationRepository reservations;

	private final ReservationLineRepository lines;

	public StockService(StockRepository stock, ReservationRepository reservations, ReservationLineRepository lines) {
		this.stock = stock;
		this.reservations = reservations;
		this.lines = lines;
	}

	/**
	 * Takes the stock of every line, all or nothing. Repeating a reservation id changes nothing and
	 * succeeds, so a caller may retry after a timeout. Lines with the same SKU are added up.
	 *
	 * @throws InsufficientStockException if any SKU is unknown or has too few units; the transaction
	 * rolls back, so no line changes
	 */
	@Transactional
	public void reserve(String reservationId, List<StockLine> requested) {
		if (this.reservations.insertIfAbsent(reservationId) == 0) {
			LOGGER.debug("Reservation {} already exists, nothing to do", reservationId);
			return;
		}
		Map<String, Integer> bySku = new TreeMap<>(); // sorted: the lock order
		for (StockLine line : requested) {
			bySku.merge(line.sku(), line.quantity(), Math::addExact);
		}
		for (Map.Entry<String, Integer> line : bySku.entrySet()) {
			if (this.stock.decrementIfAvailable(line.getKey(), line.getValue()) == 0) {
				throw new InsufficientStockException(line.getKey());
			}
			this.lines.save(new ReservationLine(reservationId, line.getKey(), line.getValue()));
		}
	}

	/**
	 * Gives the stock of a HELD or CONFIRMED reservation back and forgets the reservation.
	 *
	 * @throws ReservationShippedException if the reservation has shipped
	 * @throws ReservationNotFoundException if there is no such reservation
	 */
	@Transactional
	public void release(String reservationId) {
		Reservation reservation = this.reservations.findForUpdate(reservationId)
				.orElseThrow(() -> new ReservationNotFoundException(reservationId));
		if (reservation.getStatus() == ReservationStatus.SHIPPED) {
			throw new ReservationShippedException(reservationId);
		}
		giveBack(reservation);
	}

	/**
	 * Releases a reservation only if it is still HELD and was created before {@code cutoff}. The expiry job
	 * finds candidates first and calls this per id, so a reservation confirmed in between is left alone.
	 * Runs in its own transaction when called from outside one.
	 *
	 * @return whether the reservation was released
	 */
	@Transactional
	public boolean releaseIfStillHeld(String reservationId, Instant cutoff) {
		return this.reservations.findForUpdate(reservationId)
				.filter(r -> r.getStatus() == ReservationStatus.HELD && r.getCreatedAt().isBefore(cutoff))
				.map(r -> {
					giveBack(r);
					return true;
				})
				.orElse(false);
	}

	private void giveBack(Reservation reservation) {
		String reservationId = reservation.getReservationId();
		for (ReservationLine line : this.lines.findByReservationIdOrderBySku(reservationId)) {
			this.stock.increment(line.getSku(), line.getQuantity());
		}
		this.lines.deleteByReservation(reservationId);
		this.reservations.delete(reservation);
	}

	/**
	 * HELD to CONFIRMED. Does nothing when the reservation is unknown or already CONFIRMED or SHIPPED:
	 * a late or repeated event must neither fail nor move a reservation backwards.
	 *
	 * @return whether the status changed
	 */
	@Transactional
	public boolean confirm(String reservationId) {
		boolean changed = this.reservations.markConfirmed(reservationId) == 1;
		if (!changed) {
			LOGGER.info("Reservation {} not confirmed: unknown or already past HELD", reservationId);
		}
		return changed;
	}

	/**
	 * HELD or CONFIRMED to SHIPPED (HELD is allowed so that a confirmation delivered after the shipment
	 * cannot leave a reservation stuck). Does nothing for an unknown or already shipped reservation.
	 *
	 * @return whether the status changed
	 */
	@Transactional
	public boolean ship(String reservationId) {
		boolean changed = this.reservations.markShipped(reservationId) == 1;
		if (!changed) {
			LOGGER.info("Reservation {} not shipped: unknown or already shipped", reservationId);
		}
		return changed;
	}

	/** Units of a SKU that can still be reserved. */
	@Transactional(readOnly = true)
	public int available(String sku) {
		return this.stock.findById(sku).map(Stock::getAvailable).orElseThrow(() -> new UnknownSkuException(sku));
	}

}

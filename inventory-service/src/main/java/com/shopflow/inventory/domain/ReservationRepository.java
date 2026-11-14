package com.shopflow.inventory.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ReservationRepository extends JpaRepository<Reservation, String> {

	/**
	 * Creates a HELD reservation unless the id exists. Returns 0 for a repeat. If a concurrent
	 * transaction with the same id is still open, this statement waits for it to finish.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			insert into reservation (reservation_id, status, created_at, updated_at)
			values (:id, 'HELD', now(), now())
			on conflict (reservation_id) do nothing
			""", nativeQuery = true)
	int insertIfAbsent(@Param("id") String id);

	/** HELD to CONFIRMED. Returns 0 when the reservation is absent or already further along. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			update reservation set status = 'CONFIRMED', updated_at = now()
			where reservation_id = :id and status = 'HELD'
			""", nativeQuery = true)
	int markConfirmed(@Param("id") String id);

	/** HELD or CONFIRMED to SHIPPED. Returns 0 when the reservation is absent or already shipped. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			update reservation set status = 'SHIPPED', updated_at = now()
			where reservation_id = :id and status in ('HELD', 'CONFIRMED')
			""", nativeQuery = true)
	int markShipped(@Param("id") String id);

	/** Locks the row so a release cannot interleave with a status change of the same reservation. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Reservation r where r.reservationId = :id")
	Optional<Reservation> findForUpdate(@Param("id") String id);


/** Ids of HELD reservations created before the cutoff, oldest first. */
@Query("select r.reservationId from Reservation r where r.status = com.shopflow.inventory.domain.ReservationStatus.HELD and r.createdAt < :cutoff order by r.createdAt")
List<String> findHeldIdsCreatedBefore(@Param("cutoff") Instant cutoff);
}
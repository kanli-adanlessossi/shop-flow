package com.shopflow.inventory.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationLineRepository extends JpaRepository<ReservationLine, Long> {

	List<ReservationLine> findByReservationIdOrderBySku(String reservationId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from ReservationLine l where l.reservationId = :id")
	int deleteByReservation(@Param("id") String id);

}

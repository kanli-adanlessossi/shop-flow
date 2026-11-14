package com.shopflow.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Stock changes are single UPDATE statements, so PostgreSQL's row lock makes them safe across
 * threads and instances: no read-modify-write in Java, no in-process lock.
 */
public interface StockRepository extends JpaRepository<Stock, String> {

	/** Takes {@code quantity} units if, and only if, that many are available. Returns 1 on success, 0 otherwise. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update Stock s set s.available = s.available - :quantity where s.sku = :sku and s.available >= :quantity")
	int decrementIfAvailable(@Param("sku") String sku, @Param("quantity") int quantity);

	/** Gives {@code quantity} units back. Returns 1 if the SKU exists. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update Stock s set s.available = s.available + :quantity where s.sku = :sku")
	int increment(@Param("sku") String sku, @Param("quantity") int quantity);

}

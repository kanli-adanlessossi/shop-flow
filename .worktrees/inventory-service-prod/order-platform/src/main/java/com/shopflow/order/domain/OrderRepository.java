package com.shopflow.order.domain;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

	/** All orders as list-view summaries with their totals, one page at a time. */
	@Query(value = """
			select o.id as id, o.customerId as customerId, o.status as status, o.createdAt as createdAt,
			       coalesce(sum(l.quantity * l.unitPrice), 0) as total
			from Order o left join o.lines l
			group by o.id, o.customerId, o.status, o.createdAt
			""",
			countQuery = "select count(o) from Order o")
	Page<OrderSummary> findSummaries(Pageable pageable);

	/** Orders in a given status as list-view summaries with their totals, one page at a time. */
	@Query(value = """
			select o.id as id, o.customerId as customerId, o.status as status, o.createdAt as createdAt,
			       coalesce(sum(l.quantity * l.unitPrice), 0) as total
			from Order o left join o.lines l
			where o.status = :status
			group by o.id, o.customerId, o.status, o.createdAt
			""",
			countQuery = "select count(o) from Order o where o.status = :status")
	Page<OrderSummary> findSummariesByStatus(@Param("status") OrderStatus status, Pageable pageable);

	/** One customer's orders as list-view summaries with their totals, one page at a time. */
	@Query(value = """
			select o.id as id, o.customerId as customerId, o.status as status, o.createdAt as createdAt,
			       coalesce(sum(l.quantity * l.unitPrice), 0) as total
			from Order o left join o.lines l
			where o.customerId = :customerId
			group by o.id, o.customerId, o.status, o.createdAt
			""",
			countQuery = "select count(o) from Order o where o.customerId = :customerId")
	Page<OrderSummary> findSummariesByCustomerId(@Param("customerId") String customerId, Pageable pageable);

	/** One customer's orders in a given status as list-view summaries with their totals, one page at a time. */
	@Query(value = """
			select o.id as id, o.customerId as customerId, o.status as status, o.createdAt as createdAt,
			       coalesce(sum(l.quantity * l.unitPrice), 0) as total
			from Order o left join o.lines l
			where o.customerId = :customerId and o.status = :status
			group by o.id, o.customerId, o.status, o.createdAt
			""",
			countQuery = "select count(o) from Order o where o.customerId = :customerId and o.status = :status")
	Page<OrderSummary> findSummariesByCustomerIdAndStatus(@Param("customerId") String customerId,
			@Param("status") OrderStatus status, Pageable pageable);

	/** Ids of the oldest orders in a status created before the cutoff. */
	@Query("select o.id from Order o where o.status = :status and o.createdAt < :cutoff order by o.createdAt, o.id")
	List<Long> findIdsByStatusCreatedBefore(@Param("status") OrderStatus status, @Param("cutoff") Instant cutoff,
			Limit limit);

	/** Ids of cancelled orders whose stock release has not been confirmed yet, oldest first. */
	@Query("select o.id from Order o where o.stockReleasePending = true order by o.id")
	List<Long> findIdsWithStockReleasePending(Limit limit);

	/** Clears the flag without touching the version, so it never collides with a concurrent change to the order. */
	@Modifying
	@Query("update Order o set o.stockReleasePending = false where o.id = :id")
	int clearStockReleasePending(@Param("id") long id);

}

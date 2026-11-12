package com.shopflow.order.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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

}

package com.shopflow.order.infra;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.shopflow.order.domain.DailySales;

@Repository
public class DailySalesQuery {

	private final JdbcClient jdbc;

	public DailySalesQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Orders, units sold and revenue per day (UTC) between {@code from} and {@code to},
	 * both inclusive. Cancelled orders are left out; days without sales are not returned.
	 */
	public List<DailySales> dailySales(LocalDate from, LocalDate to) {
		return this.jdbc
				.sql("""
						select cast(o.created_at at time zone 'UTC' as date) as sales_date,
						       count(distinct o.id)                          as order_count,
						       coalesce(sum(l.quantity), 0)                  as unit_count,
						       coalesce(sum(l.quantity * l.unit_price), 0)   as revenue
						from orders o
						join order_lines l on l.order_id = o.id
						where o.created_at >= :from
						  and o.created_at <  :to
						  and o.status <> :cancelled
						group by cast(o.created_at at time zone 'UTC' as date)
						order by sales_date
						""")
				// OffsetDateTime, not Instant: the PostgreSQL driver cannot infer a type for Instant.
				.param("from", from.atStartOfDay().atOffset(ZoneOffset.UTC))
				.param("to", to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC))
				.param("cancelled", "CANCELLED")
				.query(DailySales.class)
				.list();
	}

}

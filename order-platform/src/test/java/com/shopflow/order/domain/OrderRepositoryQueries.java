package com.shopflow.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * The queries of {@link OrderRepository} and the order/lines mapping, against a real schema: Flyway
 * runs the migrations and Hibernate validates the entities against them (spring.jpa.hibernate.ddl-auto
 * is {@code validate}). The tests live here once and run on two databases, see the subclasses.
 *
 * <p>Each test is a transaction that is rolled back, so tests do not see each other's rows. The
 * entity manager is cleared before a query is run, so results come from the database and not from
 * the first-level cache.
 */
// Needed although @DataJpaTest on the subclasses is @Transactional too: the test transaction is
// looked up on the class that DECLARES the test method, which is this one. Without it no test
// transaction starts (TestEntityManager fails) and rows would be committed between tests.
@Transactional
abstract class OrderRepositoryQueries {

	private static final Instant BASE = Instant.parse("2026-11-01T10:00:00Z");

	@Autowired
	OrderRepository orders;

	@Autowired
	TestEntityManager em;

	@Autowired
	JdbcTemplate jdbc;

	// ---- list view with totals ----------------------------------------------------------

	@Test
	void summariesSumQuantityTimesUnitPriceOfEachOrder() {
		Order twoLines = save("alice", OrderStatus.PENDING_PAYMENT, BASE, line("A", 2, "10.50"), line("B", 1, "3.25"));
		Order oneLine = save("bob", OrderStatus.CONFIRMED, BASE, line("C", 5, "1.99"));
		Order noLines = save("carol", OrderStatus.PENDING_PAYMENT, BASE);

		Page<OrderSummary> page = this.orders.findSummaries(PageRequest.of(0, 10, Sort.by("id")));

		assertThat(page.getContent()).extracting(OrderSummary::getId)
				.containsExactly(id(twoLines), id(oneLine), id(noLines));
		// 2 x 10.50 + 1 x 3.25 = 24.25: lines are joined, not counted twice.
		assertThat(page.getContent().get(0).getTotal()).isEqualByComparingTo("24.25");
		assertThat(page.getContent().get(1).getTotal()).isEqualByComparingTo("9.95");
		// An order without lines is still listed, with a total of zero (left join + coalesce).
		assertThat(page.getContent().get(2).getTotal()).isEqualByComparingTo("0");
	}

	@Test
	void summariesCarryTheListColumns() {
		Order order = save("alice", OrderStatus.CONFIRMED, BASE.plusSeconds(5), line("A", 1, "2.00"));

		OrderSummary summary = this.orders.findSummaries(PageRequest.of(0, 10)).getContent().get(0);

		assertThat(summary.getId()).isEqualTo(id(order));
		assertThat(summary.getCustomerId()).isEqualTo("alice");
		assertThat(summary.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
		assertThat(summary.getCreatedAt()).isEqualTo(BASE.plusSeconds(5));
	}

	@Test
	void summariesArePagedWithTheTotalCountOfAllOrders() {
		List<Order> saved = List.of(
				save("a", OrderStatus.PENDING_PAYMENT, BASE), save("b", OrderStatus.PENDING_PAYMENT, BASE),
				save("c", OrderStatus.PENDING_PAYMENT, BASE), save("d", OrderStatus.PENDING_PAYMENT, BASE),
				save("e", OrderStatus.PENDING_PAYMENT, BASE));

		Page<OrderSummary> second = this.orders.findSummaries(PageRequest.of(1, 2, Sort.by("id")));

		assertThat(second.getContent()).extracting(OrderSummary::getId).containsExactly(id(saved.get(2)), id(saved.get(3)));
		assertThat(second.getTotalElements()).isEqualTo(5);
		assertThat(second.getTotalPages()).isEqualTo(3);
	}

	@Test
	void summariesCanBeSortedByTheTotalAndByCustomer() {
		Order cheap = save("zed", OrderStatus.PENDING_PAYMENT, BASE, line("A", 1, "1.00"));
		Order dear = save("amy", OrderStatus.PENDING_PAYMENT, BASE, line("B", 3, "9.00"));
		Order middle = save("max", OrderStatus.PENDING_PAYMENT, BASE, line("C", 1, "5.00"));

		Page<OrderSummary> byTotal = this.orders.findSummaries(PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "total")));
		Page<OrderSummary> byCustomer = this.orders.findSummaries(PageRequest.of(0, 10, Sort.by("customerId")));

		assertThat(byTotal.getContent()).extracting(OrderSummary::getId).containsExactly(id(dear), id(middle), id(cheap));
		assertThat(byCustomer.getContent()).extracting(OrderSummary::getCustomerId).containsExactly("amy", "max", "zed");
	}

	@Test
	void summariesByStatusListOnlyThatStatusAndCountOnlyThatStatus() {
		Order pending1 = save("a", OrderStatus.PENDING_PAYMENT, BASE, line("A", 1, "1.00"));
		Order pending2 = save("b", OrderStatus.PENDING_PAYMENT, BASE, line("A", 2, "1.00"));
		save("c", OrderStatus.CONFIRMED, BASE, line("A", 1, "1.00"));
		save("d", OrderStatus.CANCELLED, BASE);

		Page<OrderSummary> page = this.orders.findSummariesByStatus(OrderStatus.PENDING_PAYMENT,
				PageRequest.of(0, 1, Sort.by("id")));

		assertThat(page.getContent()).extracting(OrderSummary::getId).containsExactly(id(pending1));
		assertThat(page.getTotalElements()).isEqualTo(2);
		assertThat(this.orders.findSummariesByStatus(OrderStatus.PENDING_PAYMENT, PageRequest.of(1, 1, Sort.by("id")))
				.getContent()).extracting(OrderSummary::getId).containsExactly(id(pending2));
		assertThat(this.orders.findSummariesByStatus(OrderStatus.SHIPPED, PageRequest.of(0, 10)).getContent()).isEmpty();
	}

	// ---- orders to clean up -------------------------------------------------------------

	@Test
	void idsByStatusCreatedBeforeReturnsOldOrdersOfThatStatusOldestFirst() {
		Order newest = save("a", OrderStatus.PENDING_PAYMENT, BASE.plus(3, ChronoUnit.HOURS));
		Order middle = save("b", OrderStatus.PENDING_PAYMENT, BASE.plus(2, ChronoUnit.HOURS));
		Order oldest = save("c", OrderStatus.PENDING_PAYMENT, BASE.plus(1, ChronoUnit.HOURS));
		save("d", OrderStatus.CONFIRMED, BASE);       // old, but paid: not selected
		save("e", OrderStatus.CANCELLED, BASE);       // old, but already cancelled: not selected
		Instant cutoff = BASE.plus(3, ChronoUnit.HOURS);

		List<Long> ids = this.orders.findIdsByStatusCreatedBefore(OrderStatus.PENDING_PAYMENT, cutoff, Limit.of(10));

		// The newest is created exactly at the cutoff, so it is not "before" it.
		assertThat(ids).containsExactly(id(oldest), id(middle));
		assertThat(newest).isNotNull();
	}

	@Test
	void idsByStatusCreatedBeforeStopsAtTheLimitAndBreaksTiesById() {
		Order first = save("a", OrderStatus.PENDING_PAYMENT, BASE);
		Order second = save("b", OrderStatus.PENDING_PAYMENT, BASE);
		save("c", OrderStatus.PENDING_PAYMENT, BASE);

		List<Long> ids = this.orders.findIdsByStatusCreatedBefore(OrderStatus.PENDING_PAYMENT, BASE.plusSeconds(1),
				Limit.of(2));

		assertThat(ids).containsExactly(id(first), id(second));
	}

	// ---- mapping ------------------------------------------------------------------------

	@Test
	void anOrderIsSavedWithItsLinesAndReadBackUnchanged() {
		Order saved = save("alice", OrderStatus.PENDING_PAYMENT, BASE.plusNanos(123_456_789), line("A", 2, "10.50"));
		Instant createdAt = saved.getCreatedAt();

		this.em.clear();
		Order loaded = this.orders.findById(id(saved)).orElseThrow();

		assertThat(loaded.getCustomerId()).isEqualTo("alice");
		assertThat(loaded.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
		assertThat(loaded.getReservationId()).isNotBlank();
		assertThat(loaded.getLines()).singleElement().satisfies((l) -> {
			assertThat(l.getSku()).isEqualTo("A");
			assertThat(l.getQuantity()).isEqualTo(2);
			assertThat(l.getUnitPrice()).isEqualByComparingTo("10.50");
		});
		// The column keeps microseconds and ROUNDS the rest (.123456789 -> .123457, on H2 and
		// PostgreSQL alike). That is why Order truncates createdAt to microseconds when it is created
		// (see the next test): otherwise a response built before the first reload would show a
		// different value than every later read.
		assertThat(createdAt).isEqualTo(BASE.plusNanos(123_456_789));
		assertThat(loaded.getCreatedAt()).isEqualTo(BASE.plusNanos(123_457_000));
	}

	@Test
	void aNewOrderIsCreatedAndReadBackWithTheMicrosecondTimestampItWasCreatedWith() {
		Order order = new Order("alice", null, UUID.randomUUID().toString());
		Instant before = order.getCreatedAt();
		this.orders.saveAndFlush(order);

		this.em.clear();

		assertThat(this.orders.findById(id(order)).orElseThrow().getCreatedAt()).isEqualTo(before);
		assertThat(before).isEqualTo(before.truncatedTo(ChronoUnit.MICROS));
	}

	@Test
	void replacingTheContentDeletesTheLinesThatAreGone() {
		Order order = save("alice", OrderStatus.PENDING_PAYMENT, BASE, line("A", 1, "1.00"), line("B", 1, "2.00"));
		this.em.clear();
		assertThat(lineCount(id(order))).isEqualTo(2);

		Order loaded = this.orders.findById(id(order)).orElseThrow();
		loaded.replaceContent("alice", "SPRING", List.of(line("C", 4, "0.50")));
		this.orders.flush();

		assertThat(lineCount(id(order))).isEqualTo(1);
		this.em.clear();
		assertThat(this.orders.findById(id(order)).orElseThrow().getLines()).extracting(OrderLine::getSku)
				.containsExactly("C");
	}

	@Test
	void deletingAnOrderDeletesItsLines() {
		Order order = save("alice", OrderStatus.CANCELLED, BASE, line("A", 1, "1.00"), line("B", 1, "2.00"));
		Order other = save("bob", OrderStatus.PENDING_PAYMENT, BASE, line("C", 1, "3.00"));
		this.em.clear();

		this.orders.delete(this.orders.findById(id(order)).orElseThrow());
		this.orders.flush();

		assertThat(lineCount(id(order))).isZero();
		assertThat(lineCount(id(other))).isEqualTo(1);
		assertThat(this.orders.findById(id(order))).isEmpty();
	}

	// ---- helpers ------------------------------------------------------------------------

	/** Saves an order with the given status and creation time, then clears the entity manager. */
	private Order save(String customerId, OrderStatus status, Instant createdAt, OrderLine... lines) {
		Order order = new Order(customerId, null, UUID.randomUUID().toString());
		order.setStatus(status);
		// There is no setter: createdAt is fixed when the order is created. The tests need other times.
		ReflectionTestUtils.setField(order, "createdAt", createdAt);
		for (OrderLine line : lines) {
			order.addLine(line);
		}
		this.orders.saveAndFlush(order);
		this.em.clear();
		return order;
	}

	private static OrderLine line(String sku, int quantity, String unitPrice) {
		return new OrderLine(sku, quantity, new BigDecimal(unitPrice));
	}

	private static Long id(Order order) {
		return Objects.requireNonNull(order.getId());
	}

	private int lineCount(Long orderId) {
		return Objects.requireNonNull(
				this.jdbc.queryForObject("select count(*) from order_lines where order_id = ?", Integer.class, orderId));
	}

}

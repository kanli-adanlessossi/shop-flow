package com.shopflow.inventory.domain;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.shopflow.inventory.TestcontainersConfiguration;

/**
 * {@link StockService} and the stock queries on PostgreSQL 17, the database the service runs on:
 * Flyway builds the schema, Hibernate validates it. Every service call commits (no test-wide
 * transaction), because the point is what the database does with parallel transactions. Each test
 * uses its own random SKUs and reservation ids, so tests do not see each other's rows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, StockService.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class StockRepositoryPostgresTest {

	@Autowired
	StockService service;

	@Autowired
	JdbcTemplate jdbc;

	private String sku(int available) {
		String sku = "T-" + UUID.randomUUID();
		this.jdbc.update("insert into stock (sku, available) values (?, ?)", sku, available);
		return sku;
	}

	private static String id() {
		return "r-" + UUID.randomUUID();
	}

	private int available(String sku) {
		return this.service.available(sku);
	}

	private String status(String reservationId) {
		return requireNonNull(this.jdbc.queryForObject("select status from reservation where reservation_id = ?",
				String.class, reservationId));
	}

	private int reservationCount(String reservationId) {
		return requireNonNull(this.jdbc.queryForObject("select count(*) from reservation where reservation_id = ?",
				Integer.class, reservationId));
	}

	// ---- reserve ------------------------------------------------------------------------

	@Test
	void reserveTakesTheStockAndHoldsTheReservation() {
		String sku = sku(10);
		String id = id();
		this.service.reserve(id, List.of(new StockLine(sku, 4)));
		assertThat(available(sku)).isEqualTo(6);
		assertThat(status(id)).isEqualTo("HELD");
	}

	@Test
	void theSameReservationIdTwiceTakesStockOnce() {
		String sku = sku(10);
		String id = id();
		this.service.reserve(id, List.of(new StockLine(sku, 4)));
		this.service.reserve(id, List.of(new StockLine(sku, 4)));
		assertThat(available(sku)).isEqualTo(6);
	}

	@Test
	void linesWithTheSameSkuAreAddedUp() {
		String sku = sku(10);
		this.service.reserve(id(), List.of(new StockLine(sku, 3), new StockLine(sku, 4)));
		assertThat(available(sku)).isEqualTo(3);
	}

	@Test
	void aSingleShortLineRejectsTheWholeReservationAndChangesNothing() {
		String plenty = sku(10);
		String scarce = sku(1);
		String id = id();
		assertThatThrownBy(() -> this.service.reserve(id, List.of(new StockLine(plenty, 5), new StockLine(scarce, 2))))
				.isInstanceOf(InsufficientStockException.class);
		assertThat(available(plenty)).isEqualTo(10);
		assertThat(available(scarce)).isEqualTo(1);
		assertThat(reservationCount(id)).isZero();
	}

	@Test
	void anUnknownSkuRejectsTheWholeReservationAndChangesNothing() {
		String known = sku(10);
		String id = id();
		assertThatThrownBy(() -> this.service.reserve(id, List.of(new StockLine(known, 1), new StockLine("NO-SUCH-SKU", 1))))
				.isInstanceOf(InsufficientStockException.class);
		assertThat(available(known)).isEqualTo(10);
		assertThat(reservationCount(id)).isZero();
	}

	@Test
	void aRejectedReservationIdCanBeUsedAgainOnceStockIsThere() {
		String sku = sku(1);
		String id = id();
		assertThatThrownBy(() -> this.service.reserve(id, List.of(new StockLine(sku, 2))))
				.isInstanceOf(InsufficientStockException.class);
		this.jdbc.update("update stock set available = 5 where sku = ?", sku);
		this.service.reserve(id, List.of(new StockLine(sku, 2)));
		assertThat(available(sku)).isEqualTo(3);
	}

	@Test
	void theDatabaseRefusesNegativeStock() {
		String sku = sku(1);
		assertThatThrownBy(() -> this.jdbc.update("update stock set available = -1 where sku = ?", sku))
				.isInstanceOf(RuntimeException.class);
	}

	// ---- release ------------------------------------------------------------------------

	@Test
	void releasingAHeldReservationGivesTheStockBackAndForgetsIt() {
		String sku = sku(10);
		String id = id();
		this.service.reserve(id, List.of(new StockLine(sku, 4)));
		this.service.release(id);
		assertThat(available(sku)).isEqualTo(10);
		assertThat(status(id)).isEqualTo("RELEASED");
		assertThat(this.jdbc.queryForObject("select count(*) from reservation_line where reservation_id = ?",
				Integer.class, id)).isZero();
	}

	@Test
	void releasingAConfirmedReservationGivesTheStockBack() {
		String sku = sku(10);
		String id = id();
		this.service.reserve(id, List.of(new StockLine(sku, 4)));
		this.service.confirm(id);
		this.service.release(id);
		assertThat(available(sku)).isEqualTo(10);
		assertThat(status(id)).isEqualTo("RELEASED");
	}

	@Test
	void releasingAShippedReservationIsRefusedAndKeepsEverything() {
		String sku = sku(10);
		String id = id();
		this.service.reserve(id, List.of(new StockLine(sku, 4)));
		this.service.ship(id);
		assertThatThrownBy(() -> this.service.release(id)).isInstanceOf(ReservationShippedException.class);
		assertThat(available(sku)).isEqualTo(6);
		assertThat(status(id)).isEqualTo("SHIPPED");
	}

	@Test
	void releasingAnAbsentReservationIsNotFound() {
		assertThatThrownBy(() -> this.service.release(id())).isInstanceOf(ReservationNotFoundException.class);
	}

	@Test
	void aReleasedReservationIdCannotReserveAgainAndReleasingTwiceIsNotFound() {
		String sku = sku(10);
		String id = id();
		this.service.reserve(id, List.of(new StockLine(sku, 4)));
		this.service.release(id);
		assertThatThrownBy(() -> this.service.reserve(id, List.of(new StockLine(sku, 4))))
				.isInstanceOf(ReservationReleasedException.class);
		assertThatThrownBy(() -> this.service.release(id)).isInstanceOf(ReservationNotFoundException.class);
		assertThat(available(sku)).isEqualTo(10);
	}

	// ---- transitions --------------------------------------------------------------------

	@Test
	void statusOnlyMovesForward() {
		String id = id();
		this.service.reserve(id, List.of(new StockLine(sku(5), 1)));

		assertThat(this.service.confirm(id)).isTrue();
		assertThat(status(id)).isEqualTo("CONFIRMED");
		assertThat(this.service.confirm(id)).isFalse();

		assertThat(this.service.ship(id)).isTrue();
		assertThat(status(id)).isEqualTo("SHIPPED");

		assertThat(this.service.confirm(id)).isFalse();
		assertThat(this.service.ship(id)).isFalse();
		assertThat(status(id)).isEqualTo("SHIPPED");
	}

	@Test
	void aHeldReservationCanBeShippedDirectly() {
		String id = id();
		this.service.reserve(id, List.of(new StockLine(sku(5), 1)));
		assertThat(this.service.ship(id)).isTrue();
		assertThat(status(id)).isEqualTo("SHIPPED");
	}

	@Test
	void transitionsOfAnUnknownReservationDoNothing() {
		assertThat(this.service.confirm(id())).isFalse();
		assertThat(this.service.ship(id())).isFalse();
	}

	@Test
	void anUnknownSkuHasNoStockLevel() {
		assertThatThrownBy(() -> this.service.available("NO-SUCH-SKU")).isInstanceOf(UnknownSkuException.class);
	}

	// ---- concurrency --------------------------------------------------------------------

	@Test
	void concurrentReservationsSucceedExactlyAsOftenAsTheStockAllows() throws Exception {
		String sku = sku(10);
		int threads = 24;
		int quantity = 3; // floor(10 / 3) = 3 can succeed
		List<Boolean> outcomes = runInParallel(threads, i -> {
			try {
				this.service.reserve(id(), List.of(new StockLine(sku, quantity)));
				return true;
			}
			catch (InsufficientStockException ex) {
				return false;
			}
		});
		assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isEqualTo(3);
		assertThat(available(sku)).isEqualTo(1);
	}

	@Test
	void theSameReservationIdFromManyThreadsTakesStockOnce() throws Exception {
		String sku = sku(100);
		String id = id();
		List<Boolean> outcomes = runInParallel(16, i -> {
			this.service.reserve(id, List.of(new StockLine(sku, 5)));
			return true;
		});
		assertThat(outcomes).hasSize(16).containsOnly(true);
		assertThat(available(sku)).isEqualTo(95);
		assertThat(reservationCount(id)).isEqualTo(1);
	}

	@Test
	void reservationsNamingTheSameSkusInOppositeOrderDoNotDeadlock() throws Exception {
		String a = sku(100);
		String b = sku(100);
		List<Boolean> outcomes = runInParallel(40, i -> {
			List<StockLine> lines = (i % 2 == 0) ? List.of(new StockLine(a, 1), new StockLine(b, 1))
					: List.of(new StockLine(b, 1), new StockLine(a, 1));
			this.service.reserve(id(), lines);
			return true;
		});
		assertThat(outcomes).hasSize(40).containsOnly(true);
		assertThat(available(a)).isEqualTo(60);
		assertThat(available(b)).isEqualTo(60);
	}

	@Test
	void parallelReservationsAndReleasesNeverLoseOrInventUnits() throws Exception {
		String sku = sku(50);
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			String id = id();
			ids.add(id);
			this.service.reserve(id, List.of(new StockLine(sku, 1)));
		}
		// 50 - 20 held = 30; releasing those 20 while 20 new ones are made ends at 30 - (-20) - 20 = 30.
		runInParallel(40, i -> {
			if (i < 20) {
				this.service.release(ids.get(i));
			}
			else {
				this.service.reserve(id(), List.of(new StockLine(sku, 1)));
			}
			return true;
		});
		assertThat(available(sku)).isEqualTo(30);
	}

	/** Starts {@code count} tasks at the same moment and returns their results in task order. */
	private static <T> List<T> runInParallel(int count, IndexedTask<T> task) throws Exception {
		CountDownLatch ready = new CountDownLatch(count);
		CountDownLatch go = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				int index = i;
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return task.run(index);
				}));
			}
			assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
			go.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(60, TimeUnit.SECONDS));
			}
			return results;
		}
	}

	@FunctionalInterface
	private interface IndexedTask<T> {

		T run(int index) throws Exception;

	}

}

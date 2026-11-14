package com.shopflow.order.events;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.shopflow.order.ShopFlowProperties;

import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Sends the events recorded in the outbox to Kafka, oldest first, and marks them published.
 *
 * <p>Guarantees and limits:
 * <ul>
 * <li>At least once: if the app dies after Kafka accepted a message but before the row is marked,
 * the row is sent again. Consumers deduplicate by event id (see {@link OrderEventHandler}).</li>
 * <li>Order: rows go out in id order and one at a time, and the batch stops at the first failure,
 * so events of one order are never sent out of order. A row that Kafka keeps rejecting therefore blocks
 * every later row. This is accepted: skipping or quarantining it would let later events of the same
 * order overtake it. Instead the failure is visible: the counter {@value #FAILURES} goes up on every
 * failed attempt and a WARN line names the event. An alert on a counter that keeps rising is how
 * somebody finds a stuck pipeline; the fix is then done by hand (correct or delete the row).</li>
 * <li>A failure to mark a row published (database error after Kafka accepted the message) is logged and
 * ends the run; the next run sends the row again (at least once, see above).</li>
 * <li>Kafka down or slow: nothing is lost. Rows wait in the table, and the next run (every second)
 * tries again.</li>
 * <li>One relay at a time across instances (ShedLock). The lease ({@code lockAtMostFor}, 2 minutes) must
 * outlast a run, so a run stops starting sends after {@link #MAX_RUN} and the next run carries on.</li>
 * </ul>
 */
@Component
public class OutboxRelay {

	static final int BATCH_SIZE = 50;

	/** Counts failed attempts to send an outbox row to Kafka. */
	public static final String FAILURES = "shopflow.outbox.publish_failures";

	private static final long SEND_TIMEOUT_SECONDS = 5;

	/**
	 * A run stops starting new sends after this long and leaves the rest for the next run. With the 5 s
	 * send timeout a run lasts at most about 65 s, so it always ends inside the 2 minute lock lease.
	 */
	static final Duration MAX_RUN = Duration.ofSeconds(60);

	private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);

	private record Row(long id, String eventId, String key, String payload) {
	}

	private final JdbcClient jdbc;

	private final KafkaTemplate<String, String> kafka;

	private final ShopFlowProperties properties;

	private final Clock clock;

	private final MeterRegistry meters;

	public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, ShopFlowProperties properties,
			Clock clock, MeterRegistry meters) {
		this.jdbc = jdbc;
		this.kafka = kafka;
		this.properties = properties;
		this.clock = clock;
		this.meters = meters;
	}

	/**
	 * Runs one second after the previous run finished. It returns nothing because ShedLock cannot
	 * lock a method that returns a primitive.
	 */
	@Scheduled(fixedDelayString = "${shopflow.outbox-relay-delay:1s}")
	@SchedulerLock(name = "outboxRelay", lockAtMostFor = "2m")
	public void publishPending() {
		Instant deadline = this.clock.instant().plus(MAX_RUN);
		// A full batch means more may be waiting: keep going instead of idling a second between batches.
		while (publishBatch(deadline)) {
			// publishBatch does the work
		}
	}

	/** Sends one batch. Returns true only if the whole batch went out and it was full, so another may follow. */
	private boolean publishBatch(Instant deadline) {
		List<Row> rows = this.jdbc.sql("""
				select id, event_id, message_key, payload from outbox_events
				where published_at is null order by id limit :limit
				""")
				.param("limit", BATCH_SIZE)
				.query((rs, rowNum) -> new Row(rs.getLong("id"), rs.getString("event_id"), rs.getString("message_key"),
						rs.getString("payload")))
				.list();
		int published = 0;
		for (Row row : rows) {
			if (!this.clock.instant().isBefore(deadline)) {
				// The ShedLock lease is about to run out: another instance could start while this run is
				// still sending, and two relays at once can reorder events. Continue on the next run.
				LOGGER.warn("Outbox relay stopped after {} (sent {} of {} rows); the rest follows on the next run",
						MAX_RUN, published, rows.size());
				return false;
			}
			try {
				this.kafka.send(OrderEvents.TOPIC, row.key(), row.payload()).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return false;
			}
			catch (ExecutionException | TimeoutException | RuntimeException ex) {
				// Stop here: sending a later row first would reorder events. Retried on the next run.
				this.meters.counter(FAILURES).increment();
				LOGGER.warn("Could not publish outbox event {} ({} sent this run); will retry: {}", row.eventId(),
						published, String.valueOf(ex.getCause() != null ? ex.getCause() : ex));
				return false;
			}
			try {
				this.jdbc.sql("update outbox_events set published_at = :now where id = :id")
						.param("now", OffsetDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC))
						.param("id", row.id())
						.update();
			}
			catch (RuntimeException ex) {
				// Kafka has the message but the row stays unmarked, so the next run sends it again and
				// consumers deduplicate by event id. Stop: later rows must not overtake this one.
				LOGGER.error("Outbox event {} was sent to Kafka but could not be marked published; the next run "
						+ "sends it again: {}", row.eventId(), ex.toString());
				return false;
			}
			published++;
		}
		if (published > 0) {
			LOGGER.debug("Published {} outbox events", published);
		}
		return rows.size() == BATCH_SIZE;
	}

	/** Deletes published rows older than {@code shopflow.outbox-retention}. Daily at 03:40 UTC. */
	@Scheduled(cron = "${shopflow.outbox-purge-cron:0 40 3 * * *}", zone = "UTC")
	@SchedulerLock(name = "purgeOutbox", lockAtMostFor = "10m")
	public void purgePublished() {
		Instant cutoff = this.clock.instant().minus(this.properties.outboxRetention());
		int removed = this.jdbc.sql("delete from outbox_events where published_at is not null and published_at < :cutoff")
				.param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
				.update();
		if (removed > 0) {
			LOGGER.info("Purged {} published outbox events older than {}", removed, this.properties.outboxRetention());
		}
	}

}

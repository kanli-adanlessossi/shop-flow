package com.shopflow.order.events;

import java.time.Clock;
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

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Sends the events recorded in the outbox to Kafka, oldest first, and marks them published.
 *
 * <p>Guarantees and limits:
 * <ul>
 * <li>At least once: if the app dies after Kafka accepted a message but before the row is marked,
 * the row is sent again. Consumers deduplicate by event id (see {@link OrderEventHandler}).</li>
 * <li>Order: rows go out in id order and one at a time, and the batch stops at the first failure,
 * so events of one order are never sent out of order.</li>
 * <li>Kafka down or slow: nothing is lost. Rows wait in the table, and the next run (every second)
 * tries again.</li>
 * <li>One relay at a time across instances (ShedLock).</li>
 * </ul>
 */
@Component
public class OutboxRelay {

	static final int BATCH_SIZE = 50;

	private static final long SEND_TIMEOUT_SECONDS = 5;

	private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);

	private record Row(long id, String eventId, String key, String payload) {
	}

	private final JdbcClient jdbc;

	private final KafkaTemplate<String, String> kafka;

	private final ShopFlowProperties properties;

	private final Clock clock;

	public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, ShopFlowProperties properties,
			Clock clock) {
		this.jdbc = jdbc;
		this.kafka = kafka;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Runs one second after the previous run finished. It returns nothing because ShedLock cannot
	 * lock a method that returns a primitive.
	 */
	@Scheduled(fixedDelayString = "${shopflow.outbox-relay-delay:1s}")
	@SchedulerLock(name = "outboxRelay", lockAtMostFor = "2m")
	public void publishPending() {
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
			try {
				this.kafka.send(OrderEvents.TOPIC, row.key(), row.payload()).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
			catch (ExecutionException | TimeoutException | RuntimeException ex) {
				// Stop here: sending a later row first would reorder events. Retried on the next run.
				LOGGER.warn("Could not publish outbox event {} ({} sent this run); will retry: {}", row.eventId(),
						published, String.valueOf(ex.getCause() != null ? ex.getCause() : ex));
				return;
			}
			this.jdbc.sql("update outbox_events set published_at = :now where id = :id")
					.param("now", OffsetDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC))
					.param("id", row.id())
					.update();
			published++;
		}
		if (published > 0) {
			LOGGER.debug("Published {} outbox events", published);
		}
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

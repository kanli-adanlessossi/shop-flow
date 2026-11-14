package com.shopflow.inventory.events;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.shopflow.inventory.InventoryProperties;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Keeps the inbox from growing forever: deletes marks older than
 * {@code shopflow.inventory.processed-events-retention}. Runs daily at 03:30 UTC on one instance at a time.
 */
@Component
public class ProcessedEventsPurge {

	private static final Logger LOGGER = LoggerFactory.getLogger(ProcessedEventsPurge.class);

	private final ProcessedEvents processed;

	private final InventoryProperties properties;

	private final Clock clock;

	public ProcessedEventsPurge(ProcessedEvents processed, InventoryProperties properties, Clock clock) {
		this.processed = processed;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(cron = "${shopflow.inventory.processed-events-purge-cron:0 30 3 * * *}", zone = "UTC")
	@SchedulerLock(name = "purgeProcessedEvents", lockAtMostFor = "10m")
	public void purge() {
		Instant cutoff = this.clock.instant().minus(this.properties.processedEventsRetention());
		int removed = this.processed.deleteProcessedBefore(cutoff);
		if (removed > 0) {
			LOGGER.info("Purged {} processed-event marks older than {}", removed, this.properties.processedEventsRetention());
		}
	}

}

package com.shopflow.order.events;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.stereotype.Component;

/**
 * Receives order events for notifications. Instances of this app that share the group id
 * {@code notifications} split the topic's partitions between them.
 *
 * <p>Kafka delivers at least once, so the same event can arrive again (after a rebalance, a retry
 * or an offset reset). {@link OrderEventHandler} deduplicates by event id.
 *
 * <p><b>Failures are retried without blocking.</b> When the handler throws, the record is
 * republished to {@code order-events-retry-0}, then {@code -retry-1}, {@code -retry-2}, each time
 * after a longer delay, and finally to {@code order-events-dlt} (the dead-letter topic). The main
 * topic keeps flowing meanwhile. The price is ordering: a retried event is handled after the events
 * that arrived behind it, even for the same order, so handlers must not rely on arrival order.
 *
 * <p>Values are JSON read as {@link EventEnvelope}. A message that is not a valid envelope,
 * including one without an event type, cannot be fixed by retrying and goes straight to the
 * dead-letter topic with its original bytes. A valid envelope with a type this version does not
 * know arrives as an {@link UnknownPayload}, which the handler logs and skips.
 */
@Component
public class OrderEventListener {

	private static final Logger LOGGER = LoggerFactory.getLogger(OrderEventListener.class);

	private final OrderEventHandler handler;

	private final DeadLetters deadLetters;

	public OrderEventListener(OrderEventHandler handler, DeadLetters deadLetters) {
		this.handler = handler;
		this.deadLetters = deadLetters;
	}

	/**
	 * 4 attempts in all: the first on the main topic, then after 0.5 s, 1 s and 2 s. Retry topics
	 * get 3 partitions like the main topic, so the key still maps an order to one partition.
	 */
	@RetryableTopic(
			attempts = "4",
			backOff = @BackOff(delay = 500, multiplier = 2.0, maxDelay = 5000),
			kafkaTemplate = "retryKafkaTemplate",
			numPartitions = "3",
			topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
			// A failing dead-letter handler is logged and skipped, not retried forever.
			dltStrategy = DltStrategy.FAIL_ON_ERROR)
	@KafkaListener(topics = OrderEvents.TOPIC, groupId = OrderEventHandler.CONSUMER)
	void onOrderEvent(ConsumerRecord<String, EventEnvelope> record) {
		EventEnvelope event = record.value();
		LOGGER.info("Received {} event {} from {} (key {}, partition {}, offset {})", event.type(), event.eventId(),
				record.topic(), record.key(), record.partition(), record.offset());
		this.handler.handle(event);
	}

	/**
	 * Last stop for an event that could not be handled after all retries: {@link DeadLetters} raises
	 * the alert, stores the event in the {@code dead_letters} table and logs it. The record also stays
	 * in the dead-letter topic. Replaying it is a manual step: fix the cause, then publish the stored
	 * envelope to the main topic again (the inbox makes a repeat harmless).
	 */
	@DltHandler
	void onDeadLetter(ConsumerRecord<String, EventEnvelope> record) {
		this.deadLetters.handle(record);
	}

}

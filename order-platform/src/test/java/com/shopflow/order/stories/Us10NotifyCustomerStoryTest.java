package com.shopflow.order.stories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import com.shopflow.order.events.DeadLetters;
import com.shopflow.order.events.EventEnvelope;
import com.shopflow.order.events.OrderCancelled;
import com.shopflow.order.events.OrderConfirmed;
import com.shopflow.order.events.OrderPlaced;
import com.shopflow.order.events.Payload;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("US-10 Tell the customer about their order")
class Us10NotifyCustomerStoryTest extends StoryTestSupport {

	/** An order total no real test uses: the sender is made to fail for it. */
	private static final BigDecimal POISON_TOTAL = new BigDecimal("66.66");

	@Autowired
	KafkaTemplate<String, String> kafka;

	@Autowired
	JsonMapper json;

	@Autowired
	MeterRegistry meters;

	@Test
	@DisplayName("placed, paid and cancelled: the customer is told each time, keyed by the event id")
	void everyEventReachesTheCustomer() {
		long paid = idOf(placeAlicesOrder());
		pay(paid, new BigDecimal("24.25")).expectStatus().isOk();
		long cancelled = idOf(placeAlicesOrder());
		patchStatus(bob(), cancelled, "CANCELLED").expectStatus().isOk();

		awaitNotice(OrderPlaced.class, paid);
		awaitNotice(OrderConfirmed.class, paid);
		awaitNotice(OrderCancelled.class, cancelled);
		// The idempotency key passed to the sender is the event id stored in the outbox.
		String eventId = this.jdbc.sql("select event_id from outbox_events where message_key = :key order by id limit 1")
				.param("key", String.valueOf(paid)).query(String.class).single();
		Awaitility.await().atMost(EVENT_TIMEOUT).untilAsserted(() ->
				org.mockito.Mockito.verify(this.notifications).send(org.mockito.ArgumentMatchers.eq(eventId), any()));
	}

	@Test
	@DisplayName("an event delivered twice is handled once")
	void duplicatesAreHandledOnce() {
		long orderId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
		EventEnvelope event = new EventEnvelope(UUID.randomUUID().toString(), java.time.Instant.now(),
				new OrderPlaced(orderId, "alice", new BigDecimal("5.00")));
		String message = this.json.writeValueAsString(event);

		// Kafka is at-least-once: the same message twice is what a redelivery looks like.
		this.kafka.send("order-events", String.valueOf(orderId), message);
		this.kafka.send("order-events", String.valueOf(orderId), message);

		awaitNotice(OrderPlaced.class, orderId);
		Awaitility.await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
				.until(() -> noticesFor(orderId).size() == 1);
		assertThat(this.jdbc.sql("select count(*) from processed_events where event_id = :id")
				.param("id", event.eventId()).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	@DisplayName("a notice that keeps failing is retried, then parked as a dead letter; others keep flowing")
	void failingNoticeBecomesADeadLetter() {
		doAnswer((call) -> {
			Payload payload = call.getArgument(1);
			if (payload instanceof OrderPlaced placed && placed.total().compareTo(POISON_TOTAL) == 0) {
				throw new IllegalStateException("mail server down (test)");
			}
			return call.callRealMethod();
		}).when(this.notifications).send(anyString(), any());
		double deadLettersBefore = deadLetterCount();

		long poisoned = idOf(placeOrder(alice(), orderJson("alice", "A:1:66.66")));
		long healthy = idOf(placeAlicesOrder());

		// Non-blocking retries: the healthy order is not held up behind the failing one.
		awaitNotice(OrderPlaced.class, healthy);
		Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
				assertThat(this.jdbc.sql("select count(*) from dead_letters where message_key = :key and event_type = 'order.placed'")
						.param("key", String.valueOf(poisoned)).query(Long.class).single()).isEqualTo(1));

		// 4 attempts: the main topic and three retry topics.
		assertThat(noticesFor(poisoned)).hasSize(4);
		assertThat(deadLetterCount()).isGreaterThan(deadLettersBefore);
		assertThat(this.jdbc.sql("select count(*) from processed_events pe join outbox_events oe on oe.event_id = pe.event_id"
				+ " where oe.message_key = :key").param("key", String.valueOf(poisoned)).query(Long.class).single())
				.as("a failed event is not marked as handled").isZero();
	}

	private double deadLetterCount() {
		return this.meters.find(DeadLetters.COUNTER).counters().stream().mapToDouble(Counter::count).sum();
	}

}

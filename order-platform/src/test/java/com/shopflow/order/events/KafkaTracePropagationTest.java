package com.shopflow.order.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionOperations;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/**
 * Runs the whole path against a real broker: an event is stored in the outbox, {@link OutboxRelay}
 * sends it, {@link OrderEventListener} receives it. The test then checks the finished spans: the
 * send (producer) and the receive (consumer) must be in ONE trace, the receive must be the child of
 * the send, and the handler code must run inside that same trace.
 *
 * <p>Needs Docker (skipped without it). The Kafka container is started once and its address is handed to Spring Boot by
 * {@code @ServiceConnection}, so {@code spring.kafka.bootstrap-servers} from application.yaml is
 * replaced.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
		// Boot samples 10% by default; the test needs every trace.
		"management.tracing.sampling.probability=1.0",
		// The default relay delay is already 1 s; the first poll after the group joined is enough.
		"shopflow.outbox-relay-delay=200ms" })
@Import(KafkaTracePropagationTest.SpanCapture.class)
class KafkaTracePropagationTest {

	@Container
	@ServiceConnection
	static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2");

	static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();

	/** Feeds every finished span into {@link #SPANS}. Boot adds {@link SpanProcessor} beans to its tracer provider. */
	@TestConfiguration(proxyBeanMethods = false)
	static class SpanCapture {

		@Bean
		SpanProcessor inMemorySpanProcessor() {
			return SimpleSpanProcessor.create(SPANS);
		}

	}

	@MockitoBean
	NotificationSender sender;

	@Autowired
	OutboxEvents outbox;

	@Autowired
	TransactionOperations transactions;

	@Autowired
	Tracer tracer;

	@BeforeEach
	void clearSpans() {
		SPANS.reset();
	}

	@Test
	void traceSpansProducerAndConsumer() {
		CompletableFuture<String> traceIdInHandler = new CompletableFuture<>();
		doAnswer(call -> {
			var span = this.tracer.currentSpan();
			traceIdInHandler.complete(span != null ? span.context().traceId() : "no current span");
			return null;
		}).when(this.sender).send(any(), any());

		EventEnvelope envelope = this.transactions
				.execute(status -> this.outbox.add(new OrderPlaced(4711L, "customer-1", new BigDecimal("24.25"))));
		assertThat(envelope).isNotNull();

		// The relay runs on a schedule and the consumer group needs a moment to join.
		String handlerTraceId = traceIdInHandler.orTimeout(60, TimeUnit.SECONDS).join();
		verify(this.sender, times(1)).send(eq(envelope.eventId()), any(OrderPlaced.class));

		// A span is exported when it ends, which is just after the listener method returns.
		Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(receiveSpan()).isPresent());

		SpanData producer = sendSpan().orElseThrow(() -> new AssertionError("no producer span for " + OrderEvents.TOPIC));
		SpanData consumer = receiveSpan().orElseThrow(() -> new AssertionError("no consumer span for " + OrderEvents.TOPIC));

		// One trace across both sides, linked parent -> child.
		assertThat(consumer.getTraceId()).isEqualTo(producer.getTraceId());
		assertThat(consumer.getParentSpanId()).isEqualTo(producer.getSpanId());
		// The handler ran inside that trace too, so its logs and calls carry the same trace id.
		assertThat(handlerTraceId).isEqualTo(producer.getTraceId());
	}

	private static List<SpanData> finished() {
		return SPANS.getFinishedSpanItems();
	}

	private static Optional<SpanData> sendSpan() {
		return finished().stream()
				.filter(span -> span.getKind() == SpanKind.PRODUCER && span.getName().contains(OrderEvents.TOPIC))
				.findFirst();
	}

	private static Optional<SpanData> receiveSpan() {
		return finished().stream()
				.filter(span -> span.getKind() == SpanKind.CONSUMER && span.getName().contains(OrderEvents.TOPIC))
				.findFirst();
	}

}

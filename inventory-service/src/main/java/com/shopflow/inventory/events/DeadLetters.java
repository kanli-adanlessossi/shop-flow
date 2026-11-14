package com.shopflow.inventory.events;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * What happens to an event that stayed unhandled after every retry. Three things, in this order of
 * importance:
 *
 * <ol>
 * <li><b>Alert:</b> the counter {@code shopflow.events.dead_letters} goes up and an ERROR line is
 * logged. This always happens, even if storing fails. An alert rule on the counter ("any increase")
 * is how somebody gets paged; the project has no mail or chat channel to call directly.</li>
 * <li><b>Store:</b> a row in {@code dead_letters} with the envelope, event id and type, and the failure.
 * It is skipped if the same message was stored before (a dead letter can be delivered twice).</li>
 * <li><b>Log:</b> the ERROR line carries what is needed to find the message: key, original topic,
 * partition, offset, event id and the error.</li>
 * </ol>
 *
 * <p>This never throws: the dead-letter record also stays in the {@code order-events-dlt} topic, so
 * a failure to store it (database down) does not lose it, and throwing would only make Spring log and
 * skip it anyway. The ERROR line includes the payload in that case.
 *
 * <p>Messages that could not even be parsed do not reach this class (Spring cannot call the
 * handler without a readable value); they are kept byte for byte in the dead-letter topic and
 * Spring logs an ERROR for each.
 */
@Component
public class DeadLetters {

	/** The counter's name; tagged with the original topic. */
	public static final String COUNTER = "shopflow.events.dead_letters";

	private static final int MAX_PAYLOAD = 4000;

	private static final int MAX_ERROR = 2000;

	private static final Logger LOGGER = LoggerFactory.getLogger(DeadLetters.class);

	private final JdbcClient jdbc;

	private final JsonMapper mapper;

	private final MeterRegistry meters;

	private final Clock clock;

	public DeadLetters(JdbcClient jdbc, JsonMapper mapper, MeterRegistry meters, Clock clock) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.meters = meters;
		this.clock = clock;
	}

	public void handle(ConsumerRecord<String, EventEnvelope> record) {
		// The recoverer sets these headers. Without them (a record put on the topic by hand, for example)
		// the record's own coordinates identify it, so it can still be stored.
		String topic = originalTopic(record);
		Integer partition = originalPartition(record);
		Long offset = originalOffset(record);
		String exceptionClass = headerText(record, KafkaHeaders.EXCEPTION_CAUSE_FQCN);
		String error = headerText(record, KafkaHeaders.EXCEPTION_MESSAGE);
		EventEnvelope event = record.value();
		String payload = json(event);

		// 1) alert, whatever else happens
		Counter.builder(COUNTER).description("Events that stayed unhandled after all retries")
				.tag("topic", topic == null ? "unknown" : topic).register(this.meters).increment();

		// 2) store
		boolean stored = false;
		String storeProblem = null;
		try {
			stored = store(record, topic, partition, offset, exceptionClass, error, event, payload);
		}
		catch (RuntimeException ex) {
			Throwable root = ex;
			while (root.getCause() != null && root.getCause() != root) {
				root = root.getCause();
			}
			// The exception's own message can contain the whole SQL statement; the root cause is shorter.
			storeProblem = ex.getClass().getSimpleName() + " (" + root.getClass().getSimpleName() + ": "
					+ cut(String.valueOf(root.getMessage()), 200) + ")";
		}

		// 3) log
		if (storeProblem != null) {
			LOGGER.error("DEAD LETTER (NOT stored: {}; it is still in the dead-letter topic) key={} from {}-{}@{} event={} error={} payload={}",
					storeProblem, record.key(), topic, partition, offset, event == null ? null : event.eventId(), error, payload);
		}
		else {
			LOGGER.error("DEAD LETTER {} key={} from {}-{}@{} event={} type={} error={}", stored ? "(stored)" : "(already stored)",
					record.key(), topic, partition, offset, event == null ? null : event.eventId(),
					event == null ? null : event.type(), error);
		}
	}

	private boolean store(ConsumerRecord<String, EventEnvelope> record, @Nullable String topic, @Nullable Integer partition,
			@Nullable Long offset, @Nullable String exceptionClass, @Nullable String error, @Nullable EventEnvelope event,
			String payload) {
		int inserted = this.jdbc.sql("""
				insert into dead_letters (original_topic, original_partition, original_offset, message_key, event_id,
				                          event_type, payload, exception_class, error_message, dead_lettered_at)
				select :topic, :partition, :offset, :key, :eventId, :type, :payload, :exceptionClass, :error, :at
				where not exists (select 1 from dead_letters
				                  where original_topic = :topic and original_partition = :partition and original_offset = :offset)
				""")
				.param("topic", topic)
				.param("partition", partition)
				.param("offset", offset)
				.param("key", record.key())
				.param("eventId", event == null ? null : event.eventId())
				.param("type", event == null ? null : event.type())
				.param("payload", cut(payload, MAX_PAYLOAD))
				.param("exceptionClass", exceptionClass)
				.param("error", error == null ? null : cut(error, MAX_ERROR))
				// Bound as OffsetDateTime: the PostgreSQL driver cannot bind java.time.Instant.
				.param("at", OffsetDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC))
				.update();
		return inserted == 1;
	}

	private String json(@Nullable EventEnvelope event) {
		if (event == null) {
			return "";
		}
		try {
			return this.mapper.writeValueAsString(event);
		}
		catch (RuntimeException ex) {
			return event.toString();
		}
	}

	static String originalTopic(ConsumerRecord<?, ?> record) {
		String header = headerText(record, KafkaHeaders.ORIGINAL_TOPIC);
		return header != null ? header : record.topic();
	}

	static Integer originalPartition(ConsumerRecord<?, ?> record) {
		Integer header = headerInt(record, KafkaHeaders.ORIGINAL_PARTITION);
		return header != null ? header : record.partition();
	}

	static Long originalOffset(ConsumerRecord<?, ?> record) {
		Long header = headerLong(record, KafkaHeaders.ORIGINAL_OFFSET);
		return header != null ? header : record.offset();
	}

	private static String cut(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max);
	}

	private static @Nullable String headerText(ConsumerRecord<?, ?> record, String name) {
		Header header = record.headers().lastHeader(name);
		return (header == null || header.value() == null) ? null : new String(header.value(), StandardCharsets.UTF_8);
	}

	private static @Nullable Integer headerInt(ConsumerRecord<?, ?> record, String name) {
		Header header = record.headers().lastHeader(name);
		return (header == null || header.value() == null || header.value().length != Integer.BYTES) ? null
				: ByteBuffer.wrap(header.value()).getInt();
	}

	private static @Nullable Long headerLong(ConsumerRecord<?, ?> record, String name) {
		Header header = record.headers().lastHeader(name);
		return (header == null || header.value() == null || header.value().length != Long.BYTES) ? null
				: ByteBuffer.wrap(header.value()).getLong();
	}

}

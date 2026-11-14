package com.shopflow.order.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * A dead letter is stored under topic, partition and offset (all {@code not null} in the table). When the
 * recoverer's headers are missing, the record's own coordinates must be used instead of dropping the row.
 */
class DeadLetterCoordinatesTest {

	@Test
	void aRecordWithoutHeadersFallsBackToItsOwnCoordinates() {
		ConsumerRecord<String, String> record = new ConsumerRecord<>("order-events-dlt", 2, 41L, "k", "v");

		assertThat(DeadLetters.originalTopic(record)).isEqualTo("order-events-dlt");
		assertThat(DeadLetters.originalPartition(record)).isEqualTo(2);
		assertThat(DeadLetters.originalOffset(record)).isEqualTo(41L);
	}

	@Test
	void theRecoverersHeadersWinWhenPresent() {
		ConsumerRecord<String, String> record = new ConsumerRecord<>("order-events-dlt", 2, 41L, "k", "v");
		record.headers().add(KafkaHeaders.ORIGINAL_TOPIC, "order-events".getBytes());
		record.headers().add(KafkaHeaders.ORIGINAL_PARTITION, ByteBuffer.allocate(Integer.BYTES).putInt(0, 5).array());
		record.headers().add(KafkaHeaders.ORIGINAL_OFFSET, ByteBuffer.allocate(Long.BYTES).putLong(0, 99L).array());

		assertThat(DeadLetters.originalTopic(record)).isEqualTo("order-events");
		assertThat(DeadLetters.originalPartition(record)).isEqualTo(5);
		assertThat(DeadLetters.originalOffset(record)).isEqualTo(99L);
	}
}

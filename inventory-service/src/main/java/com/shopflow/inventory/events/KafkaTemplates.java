package com.shopflow.inventory.events;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/**
 * The two ways this app writes to Kafka.
 *
 * <p>Boot only creates its {@code KafkaTemplate} when the context has none, so declaring the retry
 * template below would remove it. Both are therefore declared here, on top of Boot's producer
 * settings (broker address and other {@code spring.kafka.producer} properties still apply).
 */
@Configuration(proxyBeanMethods = false)
class KafkaTemplates {

	/** Strings in, strings out. Used for plain string messages; kept so Boot's own template stays available. */
	@Bean
	@SuppressWarnings("unchecked")
	KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<?, ?> producerFactory) {
		return observed(new KafkaTemplate<>((ProducerFactory<String, String>) producerFactory));
	}

	/**
	 * Boot switches observation on only for the template it creates itself, so ours must do it: each
	 * send then becomes a span and the trace context travels in the record's {@code traceparent}
	 * header, which the listener (observation enabled in application.yaml) continues.
	 */
	private static <V> KafkaTemplate<String, V> observed(KafkaTemplate<String, V> template) {
		template.setObservationEnabled(true);
		return template;
	}

	/**
	 * Used by Spring's retry-topic machinery to move a failed record to the next retry topic or the
	 * dead-letter topic. What it is asked to write depends on the record:
	 * <ul>
	 * <li>an {@link EventEnvelope} the listener understood: written back as the same JSON, without a
	 * type header, matching how the consumer reads (fixed target type);</li>
	 * <li>the raw bytes of a message that could not be read at all: written unchanged, so the
	 * dead-letter topic keeps exactly what was sent and the message can be inspected or replayed.</li>
	 * </ul>
	 */
	@Bean
	KafkaTemplate<String, Object> retryKafkaTemplate(ProducerFactory<?, ?> producerFactory) {
		Map<String, Object> config = new HashMap<>(producerFactory.getConfigurationProperties());
		// The serializers are passed as instances below; do not also name them in the config.
		config.remove(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG);
		config.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
		JacksonJsonSerializer<EventEnvelope> json = new JacksonJsonSerializer<>();
		json.setAddTypeInfo(false);
		DelegatingByTypeSerializer value = new DelegatingByTypeSerializer(
				Map.of(byte[].class, new ByteArraySerializer(), EventEnvelope.class, json));
		return observed(new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config, new StringSerializer(), value)));
	}

}

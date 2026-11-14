package com.shopflow.order.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** The topic order events go to, created at startup when the broker is reachable. */
@Configuration(proxyBeanMethods = false)
public class OrderEvents {

	public static final String TOPIC = "order-events";

	/** KafkaAdmin creates this if missing. One broker, so one replica; three partitions for parallel consumers. */
	@Bean
	NewTopic orderEventsTopic() {
		return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
	}

}

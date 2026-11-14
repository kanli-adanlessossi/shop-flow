package com.shopflow.order;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real PostgreSQL and Kafka for tests, the same images as compose.yaml. {@code @Import} it into a
 * test (or a slice such as {@code @DataJpaTest}) instead of declaring containers per class.
 *
 * <p>Containers declared as beans are started by Spring when the context starts and stopped when it
 * closes. {@code @ServiceConnection} hands their addresses to Spring Boot, replacing
 * {@code spring.datasource.*} and {@code spring.kafka.bootstrap-servers}. Test classes whose
 * configuration is otherwise identical share one cached context and therefore one pair of
 * containers; a class with different properties or mocks gets its own context and its own
 * containers.
 *
 * <p>Needs Docker. Put {@code @Testcontainers(disabledWithoutDocker = true)} on the test class to
 * skip it on machines without Docker instead of failing.
 *
 * <p>Importing this starts BOTH containers, even in a slice that needs only one (a
 * {@code @DataJpaTest} also gets a Kafka broker it never uses, a few seconds extra).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	/** Matches the {@code postgres} service in compose.yaml. */
	public static final String POSTGRES_IMAGE = "postgres:17-alpine";

	/** Matches the {@code kafka} service in compose.yaml. */
	public static final String KAFKA_IMAGE = "apache/kafka:4.3.2";

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(POSTGRES_IMAGE);
	}

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return new KafkaContainer(KAFKA_IMAGE);
	}

}

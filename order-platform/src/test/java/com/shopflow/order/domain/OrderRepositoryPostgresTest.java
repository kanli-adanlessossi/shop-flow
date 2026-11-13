package com.shopflow.order.domain;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.shopflow.order.TestcontainersConfiguration;

/**
 * {@link OrderRepositoryQueries} on PostgreSQL 17, the database the app runs on, from the shared
 * {@link TestcontainersConfiguration}. {@code replace = NONE} keeps {@code @DataJpaTest} from
 * swapping the container's data source for H2. Flyway builds the schema and Hibernate validates it,
 * exactly as at startup. Skipped when Docker is not available.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class OrderRepositoryPostgresTest extends OrderRepositoryQueries {

}

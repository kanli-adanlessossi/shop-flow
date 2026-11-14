package com.shopflow.order.domain;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

/**
 * {@link OrderRepositoryQueries} on the in-memory H2 database that {@code @DataJpaTest} swaps in.
 * Fast, and needs nothing installed. H2 is more forgiving than PostgreSQL, so
 * {@link OrderRepositoryPostgresTest} runs the same tests on the real database.
 */
@DataJpaTest
class OrderRepositoryTest extends OrderRepositoryQueries {

}

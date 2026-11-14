package com.shopflow.order.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * A {@code @CacheEvict} on a {@code @Transactional} method must evict after the commit. The test uses
 * the real {@link CacheConfig} and the default transaction advice order (what Spring Boot uses), with a
 * transaction manager and a cache that only record the order of events, so it needs no database.
 * Evicting before the commit lets a concurrent reader put the old state back into the cache.
 */
@SpringJUnitConfig(CacheEvictionAfterCommitTest.Config.class)
class CacheEvictionAfterCommitTest {

	static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());

	@Autowired
	Service service;

	@BeforeEach
	void clear() {
		EVENTS.clear();
	}

	@Test
	void theEntryIsEvictedAfterTheTransactionCommitted() {
		this.service.change(1L);

		assertThat(EVENTS).containsExactly("begin", "commit", "evict 1");
	}

	@Test
	void aRolledBackChangeEvictsNothing() {
		try {
			this.service.fail(2L);
		}
		catch (IllegalStateException expected) {
			// the point is the order of events
		}

		assertThat(EVENTS).containsExactly("begin", "rollback");
	}

	static class Service {

		@CacheEvict(cacheNames = "orders", key = "#id")
		@Transactional
		public void change(long id) {
		}

		@CacheEvict(cacheNames = "orders", key = "#id")
		@Transactional
		public void fail(long id) {
			throw new IllegalStateException("boom");
		}
	}

	@Configuration
	@EnableTransactionManagement
	@Import(CacheConfig.class)
	static class Config {

		@Bean
		Service service() {
			return new Service();
		}

		@Bean
		CacheManager cacheManager() {
			return new ConcurrentMapCacheManager("orders") {
				@Override
				protected org.springframework.cache.Cache createConcurrentMapCache(String name) {
					return new ConcurrentMapCache(name, isAllowNullValues()) {
						@Override
						public void evict(Object key) {
							EVENTS.add("evict " + key);
							super.evict(key);
						}
					};
				}
			};
		}

		@Bean
		PlatformTransactionManager transactionManager() {
			return new AbstractPlatformTransactionManager() {

				@Override
				protected Object doGetTransaction() {
					return new Object();
				}

				@Override
				protected void doBegin(Object transaction, TransactionDefinition definition) {
					EVENTS.add("begin");
				}

				@Override
				protected void doCommit(DefaultTransactionStatus status) {
					EVENTS.add("commit");
				}

				@Override
				protected void doRollback(DefaultTransactionStatus status) {
					EVENTS.add("rollback");
				}
			};
		}
	}
}

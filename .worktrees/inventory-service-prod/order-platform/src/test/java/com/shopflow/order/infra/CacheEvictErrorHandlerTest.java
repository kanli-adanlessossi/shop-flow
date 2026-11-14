package com.shopflow.order.infra;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCache;

/**
 * The eviction runs after the commit, so an evict error must not reach the client of a change that is
 * saved. Other cache errors keep Spring's default of being rethrown.
 */
class CacheEvictErrorHandlerTest {

	private final CacheConfig.EvictUnreadableEntryHandler handler = new CacheConfig.EvictUnreadableEntryHandler();

	private final ConcurrentMapCache cache = new ConcurrentMapCache("orders");

	@Test
	void anEvictErrorIsLoggedAndNotRethrown() {
		assertThatCode(() -> this.handler.handleCacheEvictError(new IllegalStateException("redis down"), this.cache, 1L))
				.doesNotThrowAnyException();
	}

	@Test
	void aPutErrorIsStillRethrown() {
		assertThatThrownBy(() -> this.handler.handleCachePutError(new IllegalStateException("redis down"), this.cache, 1L, "v"))
				.isInstanceOf(IllegalStateException.class);
	}
}

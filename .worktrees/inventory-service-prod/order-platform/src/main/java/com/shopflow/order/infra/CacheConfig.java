package com.shopflow.order.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Turns on annotation-driven caching. The Caffeine and Redis settings live in the yaml files.
 *
 * <p>The cache advice runs outside the transaction advice ({@code order} is a lower number than the
 * transaction advice's {@code Ordered.LOWEST_PRECEDENCE}). A {@code @CacheEvict} on a
 * {@code @Transactional} method therefore evicts after the commit. Evicting inside the transaction would
 * let a concurrent reader re-cache the old state before the commit, and the entry would then stay stale.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching(order = CacheConfig.ADVICE_ORDER)
public class CacheConfig implements CachingConfigurer {

	/** Outside the transaction advice, which has {@code Ordered.LOWEST_PRECEDENCE}. */
	static final int ADVICE_ORDER = Ordered.LOWEST_PRECEDENCE - 100;

	@Override
	public CacheErrorHandler errorHandler() {
		return new EvictUnreadableEntryHandler();
	}

	/**
	 * A cache entry that cannot be read (for example one written by an older version of the
	 * application, or while Redis is unavailable) is treated as a miss: the entry is evicted
	 * and the value is loaded from the database again.
	 *
	 * <p>An evict error is logged and not rethrown. The eviction runs after the commit (see above), so
	 * throwing would show the client an error for a change that is saved. The entry that may stay behind
	 * is stale only until the cache's time to live ends (5 minutes for Caffeine, 10 for Redis). Put and
	 * clear errors keep the default behaviour of being rethrown.
	 */
	static class EvictUnreadableEntryHandler extends SimpleCacheErrorHandler {

		private static final Logger log = LoggerFactory.getLogger(EvictUnreadableEntryHandler.class);

		@Override
		public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
			log.warn("Cache '{}' entry {} could not be read, treating as a miss: {}",
					cache.getName(), key, exception.toString());
			try {
				cache.evict(key);
			}
			catch (RuntimeException ex) {
				log.debug("Could not evict unreadable entry {}", key, ex);
			}
		}

		@Override
		public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
			log.error("Cache '{}' entry {} could not be evicted after a committed change; it may be stale until it "
					+ "expires: {}", cache.getName(), key, exception.toString());
		}

	}

}

package com.shopflow.order.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.context.annotation.Configuration;

/** Turns on annotation-driven caching. The Caffeine and Redis settings live in the yaml files. */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfig implements CachingConfigurer {

	@Override
	public CacheErrorHandler errorHandler() {
		return new EvictUnreadableEntryHandler();
	}

	/**
	 * A cache entry that cannot be read (for example one written by an older version of the
	 * application, or while Redis is unavailable) is treated as a miss: the entry is evicted
	 * and the value is loaded from the database again. Put and evict errors keep the default
	 * behaviour of being rethrown, although in practice the Redis client queues those commands
	 * while disconnected instead of failing them.
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

	}

}

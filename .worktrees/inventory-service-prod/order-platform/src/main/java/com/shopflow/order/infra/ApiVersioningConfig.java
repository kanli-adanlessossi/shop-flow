package com.shopflow.order.infra;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.accept.ApiVersionDeprecationHandler;
import org.springframework.web.accept.StandardApiVersionDeprecationHandler;

@Configuration(proxyBeanMethods = false)
public class ApiVersioningConfig {

	/**
	 * Marks API v1 as deprecated. Responses to v1 requests carry the
	 * {@code Deprecation} and {@code Sunset} headers (RFC 9745 / RFC 8594).
	 */
	@Bean
	ApiVersionDeprecationHandler apiVersionDeprecationHandler() {
		StandardApiVersionDeprecationHandler handler = new StandardApiVersionDeprecationHandler();
		handler.configureVersion("1")
				.setDeprecationDate(ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.UTC))
				.setSunsetDate(ZonedDateTime.of(2027, 6, 30, 0, 0, 0, 0, ZoneOffset.UTC));
		return handler;
	}

}

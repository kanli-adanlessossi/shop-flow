package com.shopflow.order.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * For {@code @WebMvcTest} slices: {@link ApiExceptionHandler} counts late payments and so needs a
 * {@code MeterRegistry}, which the slice does not create. Import this configuration.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestMetrics {

	@Bean
	MeterRegistry meterRegistry() {
		return new SimpleMeterRegistry();
	}

}

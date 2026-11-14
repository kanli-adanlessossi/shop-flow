package com.shopflow.order.infra;

import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

/**
 * Turns on {@code @Retryable} and {@code @ConcurrencyLimit} for Spring beans. Nothing is
 * annotated yet, so enabling this changes no behaviour on its own.
 */
@Configuration(proxyBeanMethods = false)
@EnableResilientMethods
public class ResilienceConfig {

}

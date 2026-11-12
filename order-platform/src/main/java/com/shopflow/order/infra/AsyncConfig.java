package com.shopflow.order.infra;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Turns on {@code @Async}. Boot's {@code applicationTaskExecutor} runs the methods (virtual threads
 * here). {@code spring.task.execution.propagate-context=true} makes that executor copy the caller's
 * context, including the trace, to the async thread; it needs {@code io.micrometer:context-propagation}
 * on the classpath.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AsyncConfig {

}

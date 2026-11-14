package com.shopflow.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Demonstrates the Spring AOP self-invocation pitfall for {@code @Transactional}.
 *
 * <p>Spring applies transactions through a proxy around the bean. A call from
 * outside goes through the proxy, but a call from one method to another on
 * {@code this} bypasses it, so the {@code @Transactional} advice never runs.
 */
@SpringJUnitConfig(SelfInvocationTransactionTest.Config.class)
class SelfInvocationTransactionTest {

	@Autowired
	BrokenOrderService brokenOrderService;

	@Autowired
	FixedOrderService fixedOrderService;

	@Test
	void selfInvocationDoesNotStartTransaction() {
		// Sanity check: the bean really is a transactional proxy.
		assertThat(AopUtils.isAopProxy(brokenOrderService)).isTrue();

		// place() -> this.save(): the call skips the proxy, so no transaction.
		boolean transactionActive = brokenOrderService.place();

		assertThat(transactionActive).isFalse();
	}

	@Test
	void callingTransactionalMethodDirectlyThroughProxyStartsTransaction() {
		// Control: calling save() from outside goes through the proxy.
		assertThat(brokenOrderService.save()).isTrue();
	}

	@Test
	void movingTransactionalMethodToAnotherBeanStartsTransaction() {
		// place() -> orderWriter.save(): the call crosses a bean boundary, so it
		// goes through the proxy and the transaction starts.
		boolean transactionActive = fixedOrderService.place();

		assertThat(transactionActive).isTrue();
	}

	@Test
	void noTransactionLeaksOutsideTheCall() {
		fixedOrderService.place();

		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
	}

	// --- Broken: public method calls its own @Transactional method ---

	@Service
	static class BrokenOrderService {

		public boolean place() {
			return save(); // self-invocation, bypasses the proxy
		}

		@Transactional
		public boolean save() {
			return TransactionSynchronizationManager.isActualTransactionActive();
		}
	}

	// --- Fixed: the @Transactional method lives in another bean ---

	@Service
	static class OrderWriter {

		@Transactional
		public boolean save() {
			return TransactionSynchronizationManager.isActualTransactionActive();
		}
	}

	@Service
	static class FixedOrderService {

		private final OrderWriter orderWriter;

		FixedOrderService(OrderWriter orderWriter) {
			this.orderWriter = orderWriter;
		}

		public boolean place() {
			return orderWriter.save(); // goes through OrderWriter's proxy
		}
	}

	@Configuration
	@EnableTransactionManagement
	static class Config {

		@Bean
		BrokenOrderService brokenOrderService() {
			return new BrokenOrderService();
		}

		@Bean
		OrderWriter orderWriter() {
			return new OrderWriter();
		}

		@Bean
		FixedOrderService fixedOrderService(OrderWriter orderWriter) {
			return new FixedOrderService(orderWriter);
		}

		/** No-op transaction manager: enough to drive transaction synchronization without a database. */
		@Bean
		PlatformTransactionManager transactionManager() {
			return new AbstractPlatformTransactionManager() {

				@Override
				protected Object doGetTransaction() {
					return new Object();
				}

				@Override
				protected void doBegin(Object transaction, TransactionDefinition definition) {
				}

				@Override
				protected void doCommit(DefaultTransactionStatus status) {
				}

				@Override
				protected void doRollback(DefaultTransactionStatus status) {
				}
			};
		}
	}
}

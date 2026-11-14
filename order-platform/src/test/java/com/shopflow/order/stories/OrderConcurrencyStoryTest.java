package com.shopflow.order.stories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.shopflow.order.domain.Order;
import com.shopflow.order.domain.OrderRepository;
import com.shopflow.order.domain.OrderService;
import com.shopflow.order.domain.OrderStatus;

/**
 * Two writers that both read the same order must not both win. The race is made deterministic: a
 * transaction loads the order, another thread cancels it and commits, and only then does the first
 * transaction write. Without a version column the first write silently overwrites the cancel.
 */
@DisplayName("Concurrent changes to one order")
class OrderConcurrencyStoryTest extends StoryTestSupport {

	@Autowired
	OrderRepository orders;

	@Autowired
	OrderService service;

	@Autowired
	PlatformTransactionManager transactions;

	@Test
	@DisplayName("a write based on a stale read fails, and the other writer's change stays")
	void staleWriteIsRejected() {
		long id = idOf(placeAlicesOrder());
		TransactionTemplate template = new TransactionTemplate(this.transactions);

		assertThatThrownBy(() -> template.executeWithoutResult(status -> {
			Order stale = this.orders.findById(id).orElseThrow();
			// Another request cancels the order and commits while this one is still open.
			CompletableFuture.runAsync(() -> this.service.updateStatus(id, OrderStatus.CANCELLED)).join();
			stale.setStatus(OrderStatus.CONFIRMED);
		})).isInstanceOf(ObjectOptimisticLockingFailureException.class);

		assertThat(statusOf(id)).isEqualTo("CANCELLED");
	}

}

package com.shopflow.order;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * The inventory-service stand-in ({@link InventoryStandInContainer}) as a container bean, wired to
 * the app through {@code shopflow.inventory.base-url}. Stock reservation and release then go over
 * real HTTP through the real client, timeouts, retries and error mapping.
 */
@TestConfiguration(proxyBeanMethods = false)
public class InventoryTestcontainersConfiguration {

	@Bean
	InventoryStandInContainer inventoryStandIn() {
		return new InventoryStandInContainer();
	}

	@Bean
	DynamicPropertyRegistrar inventoryBaseUrl(InventoryStandInContainer inventory) {
		return (registry) -> registry.add("shopflow.inventory.base-url", inventory::baseUrl);
	}

}

package com.shopflow.inventory.contract;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;

import com.shopflow.inventory.InventoryIntegrationTest;

/**
 * {@link StockContract} against the running application on the shared context, with a real
 * service-account token (role inventory), the way order-platform calls it.
 */
class RealServiceContractTest extends InventoryIntegrationTest implements StockContract {

	@Value("${local.server.port}")
	int port;

	private String sku;

	@Override
	public String baseUrl() {
		return "http://localhost:" + this.port;
	}

	@Override
	public String token() {
		return serviceToken();
	}

	@Override
	public String knownSku() {
		if (this.sku == null) {
			this.sku = "CONTRACT-" + UUID.randomUUID();
			this.jdbc.sql("insert into stock (sku, available) values (?, 5)").param(this.sku).update();
		}
		return this.sku;
	}
}
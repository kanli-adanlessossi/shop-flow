package com.shopflow.inventory.contract;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * {@link StockContract} against WireMock loaded with the stubs of {@code deploy/wiremock/inventory}
 * (copied onto the test classpath by the pom). No Spring context: only the container.
 */
@Testcontainers(disabledWithoutDocker = true)
class WireMockStubContractTest implements StockContract {

	private static final int PORT = 8080;

	@Container
	static final GenericContainer<?> WIREMOCK = new GenericContainer<>("wiremock/wiremock:3.13.1")
			.withExposedPorts(PORT)
			.withCopyFileToContainer(MountableFile.forClasspathResource("wiremock/inventory"), "/home/wiremock")
			.withCommand("--global-response-templating", "--disable-gzip")
			.waitingFor(Wait.forHttp("/__admin/health").forPort(PORT).forStatusCode(200));

	@Override
	public String baseUrl() {
		return "http://" + WIREMOCK.getHost() + ":" + WIREMOCK.getMappedPort(PORT);
	}

	@Override
	public String token() {
		return null; // the stand-in ignores the token
	}

	@Override
	public String knownSku() {
		return "SKU-001";
	}
}
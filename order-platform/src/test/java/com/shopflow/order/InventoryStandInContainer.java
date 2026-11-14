package com.shopflow.order;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * The WireMock stand-in for inventory-service, with the same stubs as {@code deploy/wiremock/inventory}
 * (copied onto the test classpath by the pom): reservations succeed, releases answer 204.
 *
 * <p>A test changes the behaviour through WireMock's admin API, the way
 * {@code deploy/wiremock/wiremock.ps1} does for local runs: {@link #answerReservationsWith} adds a stub
 * with a higher priority than the file stubs, {@link #reset} goes back to the file stubs and forgets
 * the recorded requests. Its own class, not a plain {@code GenericContainer}, so it cannot be
 * confused with the other container beans when injected.
 */
public class InventoryStandInContainer extends GenericContainer<InventoryStandInContainer> {

	/** Matches deploy/wiremock/compose.yaml. */
	public static final String IMAGE = "wiremock/wiremock:3.13.1";

	private static final int PORT = 8080;

	private final HttpClient http = HttpClient.newHttpClient();

	public InventoryStandInContainer() {
		super(IMAGE);
		withExposedPorts(PORT);
		withCopyFileToContainer(MountableFile.forClasspathResource("wiremock/inventory"), "/home/wiremock");
		withCommand("--global-response-templating", "--disable-gzip");
		waitingFor(Wait.forHttp("/__admin/health").forPort(PORT).forStatusCode(200));
	}

	/** Where the app reaches it: the value for {@code shopflow.inventory.base-url}. */
	public String baseUrl() {
		return "http://" + getHost() + ":" + getMappedPort(PORT);
	}

	/** Every reservation request from now on gets this status (409 = not enough stock, 503 = down). */
	public void answerReservationsWith(int status) {
		stub("POST", "/api/stock/reservations", status);
	}

	/** Every release request from now on gets this status. */
	public void answerReleasesWith(int status) {
		stub("DELETE", "/api/stock/reservations/.*", status);
	}

	/** How many times the stand-in was asked to release this reservation. */
	public int releaseRequests(String reservationId) {
		String body = admin("POST", "/__admin/requests/count",
				"{\"method\":\"DELETE\",\"url\":\"/api/stock/reservations/" + reservationId + "\"}");
		return Integer.parseInt(body.replaceAll("(?s).*\"count\"\\s*:\\s*(\\d+).*", "$1"));
	}

	/** How many release requests arrived since the last {@link #reset}, for any reservation. */
	public int releaseRequests() {
		String body = admin("POST", "/__admin/requests/count",
				"{\"method\":\"DELETE\",\"urlPathPattern\":\"/api/stock/reservations/.*\"}");
		return Integer.parseInt(body.replaceAll("(?s).*\"count\"\\s*:\\s*(\\d+).*", "$1"));
	}

	/** How many reservation requests carried this reservation id. */
	public int reservationRequests(String reservationId) {
		String body = admin("POST", "/__admin/requests/count",
				"{\"method\":\"POST\",\"url\":\"/api/stock/reservations\",\"bodyPatterns\":"
						+ "[{\"matchesJsonPath\":\"$[?(@.reservationId == '" + reservationId + "')]\"}]}");
		return Integer.parseInt(body.replaceAll("(?s).*\"count\"\\s*:\\s*(\\d+).*", "$1"));
	}

	/** How many requests (of any kind) arrived with an {@code Authorization: Bearer <token>} header. */
	public int requestsWithBearerToken() {
		return count("{\"headers\":{\"Authorization\":{\"matches\":\"Bearer .+\"}}}");
	}

	/** How many requests (of any kind) arrived since the last {@link #reset}. */
	public int allRequests() {
		return count("{\"urlPathPattern\":\".*\"}");
	}

	private int count(String criteria) {
		String body = admin("POST", "/__admin/requests/count", criteria);
		return Integer.parseInt(body.replaceAll("(?s).*\"count\"\\s*:\\s*(\\d+).*", "$1"));
	}

	/** Back to the file stubs; recorded requests are forgotten. */
	public void reset() {
		admin("POST", "/__admin/mappings/reset", "");
		admin("DELETE", "/__admin/requests", "");
	}

	private void stub(String method, String urlPattern, int status) {
		admin("POST", "/__admin/mappings", """
				{"priority":1,"request":{"method":"%s","urlPathPattern":"%s"},
				 "response":{"status":%d,"headers":{"Content-Type":"application/problem+json"},
				             "jsonBody":{"status":%d,"title":"stubbed by test"}}}
				""".formatted(method, urlPattern, status, status));
	}

	private String admin(String method, String path, String body) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
				.header("Content-Type", "application/json")
				.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
						: HttpRequest.BodyPublishers.ofString(body))
				.build();
		try {
			HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 300) {
				throw new IllegalStateException("WireMock admin " + method + " " + path + " answered "
						+ response.statusCode() + ": " + response.body());
			}
			return response.body();
		}
		catch (IOException ex) {
			throw new IllegalStateException("WireMock admin call failed: " + method + " " + path, ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted calling WireMock admin", ex);
		}
	}

}

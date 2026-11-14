package com.shopflow.inventory.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The calls order-platform's inventory client makes, in the shape both targets can answer: a valid
 * reservation, its release and a stock query. {@link WireMockStubContractTest} runs them against the
 * committed stubs in {@code deploy/wiremock/inventory}, {@link RealServiceContractTest} against the real
 * service, so a stub that drifts from the service (or the other way round) fails one of the two.
 *
 * <p>Only the happy path is a contract here. The stubs answer every request with a success, so the
 * error paths (409 insufficient stock, 404 unknown reservation, 409 release after shipping, 400 invalid
 * body, 401/403) are asserted against the real service only, in {@code SecurityIntegrationTest} and
 * {@code StockControllerTest}. For failures the stand-in is switched by hand (see deploy/wiremock).
 */
interface StockContract {

JsonMapper JSON = JsonMapper.builder().build();

HttpClient CLIENT = HttpClient.newHttpClient();

/** Base URL of the target, without a trailing slash. */
String baseUrl();

/** The Bearer token to send, or {@code null} for a target that ignores it. */
String token();

/** A SKU the target knows. */
String knownSku();

default HttpResponse<String> call(String method, String path, String body) throws IOException, InterruptedException {
HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + path));
if (body == null) {
request.method(method, HttpRequest.BodyPublishers.noBody());
}
else {
request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
}
if (token() != null) {
request.header("Authorization", "Bearer " + token());
}
return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
}

default String reservationBody(String reservationId) {
return "{\"reservationId\":\"" + reservationId + "\",\"lines\":[{\"sku\":\"" + knownSku() + "\",\"quantity\":1}]}";
}

@Test
@DisplayName("POST /api/stock/reservations answers 200 with the reservation id")
default void reserve() throws Exception {
String id = "contract-" + UUID.randomUUID();

HttpResponse<String> response = call("POST", "/api/stock/reservations", reservationBody(id));

assertThat(response.statusCode()).isEqualTo(200);
JsonNode json = JSON.readTree(response.body());
assertThat(json.get("reservationId").isString()).isTrue();
assertThat(json.get("reservationId").asString()).isEqualTo(id);
assertThat(call("DELETE", "/api/stock/reservations/" + id, null).statusCode()).isEqualTo(204);
}

@Test
@DisplayName("DELETE /api/stock/reservations/{id} answers 204 without a body")
default void release() throws Exception {
String id = "contract-" + UUID.randomUUID();
assertThat(call("POST", "/api/stock/reservations", reservationBody(id)).statusCode()).isEqualTo(200);

HttpResponse<String> response = call("DELETE", "/api/stock/reservations/" + id, null);

assertThat(response.statusCode()).isEqualTo(204);
assertThat(response.body()).isEmpty();
}

@Test
@DisplayName("GET /api/stock/{sku} answers 200 with sku and a numeric available")
default void stockLevel() throws Exception {
HttpResponse<String> response = call("GET", "/api/stock/" + knownSku(), null);

assertThat(response.statusCode()).isEqualTo(200);
JsonNode json = JSON.readTree(response.body());
assertThat(json.get("sku").asString()).isEqualTo(knownSku());
assertThat(json.get("available").isNumber()).isTrue();
assertThat(json.get("available").asInt()).isGreaterThanOrEqualTo(0);
}
}
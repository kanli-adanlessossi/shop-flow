package com.shopflow.order.stories;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

@DisplayName("US-5 API versions")
class Us05ApiVersionsStoryTest extends StoryTestSupport {

	@Test
	@DisplayName("without a version, version 2 applies (it has a status)")
	void defaultIsVersion2() {
		long id = idOf(placeAlicesOrder());

		this.http.get().uri("/api/orders/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + alice()).exchange()
				.expectStatus().isOk()
				.expectHeader().doesNotExist("Deprecation")
				.expectBody().jsonPath("$.status").isEqualTo("PENDING_PAYMENT");
	}

	@Test
	@DisplayName("version 1 is chosen by header, query parameter or media type, and is marked deprecated")
	void version1IsDeprecated() {
		long id = idOf(placeAlicesOrder());
		String token = alice();

		this.http.get().uri("/api/orders/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("X-API-Version", "1").exchange()
				.expectStatus().isOk()
				.expectHeader().exists("Deprecation")
				.expectHeader().exists("Sunset")
				.expectBody().jsonPath("$.status").doesNotExist();
		this.http.get().uri("/api/orders/{id}?api-version=1", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.status").doesNotExist();
		this.http.get().uri("/api/orders/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header(HttpHeaders.ACCEPT, "application/vnd.shopflow.v1+json").exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.status").doesNotExist();
	}

	@Test
	@DisplayName("an unsupported version is 400")
	void unsupportedVersion() {
		this.http.get().uri("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice())
				.header("X-API-Version", "3").exchange()
				.expectStatus().isBadRequest();
	}

}

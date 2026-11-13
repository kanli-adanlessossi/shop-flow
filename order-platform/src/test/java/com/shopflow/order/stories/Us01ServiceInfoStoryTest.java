package com.shopflow.order.stories;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("US-1 Service info")
class Us01ServiceInfoStoryTest extends StoryTestSupport {

	@Test
	@DisplayName("version 2 shows the running version")
	void version2ShowsTheVersion() {
		get(alice(), "/api/health-info")
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.version").isNotEmpty()
				.jsonPath("$.profiles").exists();
	}

	@Test
	@DisplayName("version 1 shows the full build info and the profile list")
	void version1ShowsTheBuild() {
		this.http.get().uri("/api/health-info").header("Authorization", "Bearer " + alice())
				.header("X-API-Version", "1").exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.build.artifact").isEqualTo("order-platform")
				.jsonPath("$.activeProfiles").isArray();
	}

	@Test
	@DisplayName("it needs a token, unlike /actuator/health")
	void itNeedsAToken() {
		this.http.get().uri("/api/health-info").exchange().expectStatus().isUnauthorized();
		this.http.get().uri("/actuator/health").exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.status").isEqualTo("UP");
	}

}

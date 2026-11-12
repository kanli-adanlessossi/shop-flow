package com.shopflow.order.api;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthInfoController {

	private final Environment environment;
	private final BuildProperties buildProperties;

	public HealthInfoController(Environment environment, BuildProperties buildProperties) {
		this.environment = environment;
		this.buildProperties = buildProperties;
	}

	@GetMapping("/api/health-info")
	public HealthInfo healthInfo() {
		return new HealthInfo(
				List.of(environment.getActiveProfiles()),
				new BuildInfo(
						buildProperties.getGroup(),
						buildProperties.getArtifact(),
						buildProperties.getName(),
						buildProperties.getVersion(),
						buildProperties.getTime()));
	}

	public record HealthInfo(List<String> activeProfiles, BuildInfo build) {
	}

	public record BuildInfo(
			@Nullable String group,
			@Nullable String artifact,
			@Nullable String name,
			@Nullable String version,
			@Nullable Instant time) {
	}

}

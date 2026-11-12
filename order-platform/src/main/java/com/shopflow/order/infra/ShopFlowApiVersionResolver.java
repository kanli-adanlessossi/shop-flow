package com.shopflow.order.infra;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.accept.ApiVersionResolver;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Custom API version rules, first match wins:
 * <ol>
 *   <li>{@code X-API-Version} request header</li>
 *   <li>{@code api-version} query parameter</li>
 *   <li>vendor media type in {@code Accept}, e.g. {@code application/vnd.shopflow.v1+json}</li>
 * </ol>
 * A leading {@code v} is accepted everywhere ({@code v1} is the same as {@code 1}).
 * When no rule matches, the configured default version applies
 * ({@code spring.mvc.apiversion.default}).
 */
@Component
public class ShopFlowApiVersionResolver implements ApiVersionResolver {

	static final String VERSION_HEADER = "X-API-Version";
	static final String VERSION_PARAM = "api-version";

	private static final Pattern VENDOR_MEDIA_TYPE =
			Pattern.compile("application/vnd\\.shopflow\\.v([0-9]+(?:\\.[0-9]+)*)\\+json");

	@Override
	public @Nullable String resolveVersion(HttpServletRequest request) {
		String version = normalize(request.getHeader(VERSION_HEADER));
		if (version == null) {
			version = normalize(request.getParameter(VERSION_PARAM));
		}
		if (version == null) {
			version = fromAccept(request.getHeader(HttpHeaders.ACCEPT));
		}
		return version;
	}

	private static @Nullable String fromAccept(@Nullable String accept) {
		if (accept == null) {
			return null;
		}
		Matcher matcher = VENDOR_MEDIA_TYPE.matcher(accept);
		return matcher.find() ? matcher.group(1) : null;
	}

	private static @Nullable String normalize(@Nullable String raw) {
		if (raw == null) {
			return null;
		}
		String value = raw.strip();
		if (value.startsWith("v") || value.startsWith("V")) {
			value = value.substring(1);
		}
		return value.isEmpty() ? null : value;
	}

}

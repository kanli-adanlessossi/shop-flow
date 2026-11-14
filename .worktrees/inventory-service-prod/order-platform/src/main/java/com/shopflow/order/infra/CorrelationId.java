package com.shopflow.order.infra;

import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/** The correlation id of the current request: header name, MDC key and helpers. */
public final class CorrelationId {

	public static final String HEADER = "X-Correlation-Id";

	public static final String MDC_KEY = "correlationId";

	/** Anything else (spaces, control characters, very long values) is replaced, to keep logs clean. */
	private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	private CorrelationId() {
	}

	/** The id bound to the current thread, or a new one when none is (jobs, startup code, tests). */
	public static String currentOrNew() {
		String current = MDC.get(MDC_KEY);
		return (current != null) ? current : generate();
	}

	/** Keeps a caller-supplied id if it looks safe, otherwise generates one. */
	static String acceptOrGenerate(@Nullable String candidate) {
		return (candidate != null && VALID.matcher(candidate).matches()) ? candidate : generate();
	}

	private static String generate() {
		return UUID.randomUUID().toString();
	}

}

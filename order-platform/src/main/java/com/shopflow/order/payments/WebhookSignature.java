package com.shopflow.order.payments;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.Nullable;

/**
 * Signs and verifies webhook requests. The contract (invented for this project, in the style of common
 * payment providers; adapt it to the real provider):
 *
 * <pre>
 * X-Payment-Signature: t=&lt;unix seconds&gt;,v1=&lt;hex of HMAC-SHA256(secret, "&lt;t&gt;." + raw body)&gt;
 * </pre>
 *
 * <p>The signature covers the exact bytes received, so the body must be checked before it is parsed. The
 * timestamp is part of what is signed and must be within the tolerance of the server clock, which stops
 * an old captured request from being sent again.
 */
public final class WebhookSignature {

	public static final String HEADER = "X-Payment-Signature";

	private static final String ALGORITHM = "HmacSHA256";

	private WebhookSignature() {
	}

	/** The header value for a body signed at the given time. Used by tests and by tools that play the provider. */
	public static String sign(String secret, long timestampSeconds, byte[] body) {
		return "t=" + timestampSeconds + ",v1=" + HexFormat.of().formatHex(mac(secret, timestampSeconds, body));
	}

	/** Returns normally only if the signature is valid and fresh; otherwise throws. */
	public static void verify(String secret, Duration tolerance, @Nullable String header, byte[] body, Instant now) {
		if (header == null || header.isBlank()) {
			throw new InvalidWebhookSignatureException("signature header is missing");
		}
		Long timestamp = null;
		List<String> signatures = new ArrayList<>();
		for (String part : header.split(",")) {
			String[] pair = part.trim().split("=", 2);
			if (pair.length != 2) {
				continue;
			}
			if (pair[0].equals("t") && timestamp == null) {
				try {
					timestamp = Long.parseLong(pair[1]);
				}
				catch (NumberFormatException ex) {
					throw new InvalidWebhookSignatureException("timestamp is not a number");
				}
			}
			else if (pair[0].equals("v1")) {
				signatures.add(pair[1]);
			}
		}
		if (timestamp == null) {
			throw new InvalidWebhookSignatureException("timestamp is missing");
		}
		byte[] expected = HexFormat.of().formatHex(mac(secret, timestamp, body)).getBytes(StandardCharsets.US_ASCII);
		boolean matches = false;
		for (String signature : signatures) {
			// Constant-time comparison. Several v1 values are allowed: a provider rotating its secret sends both.
			matches |= MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII));
		}
		if (!matches) {
			throw new InvalidWebhookSignatureException("signature does not match");
		}
		long skew = Math.abs(now.getEpochSecond() - timestamp);
		if (skew > tolerance.toSeconds()) {
			throw new InvalidWebhookSignatureException("timestamp is " + skew + " s from the server clock");
		}
	}

	private static byte[] mac(String secret, long timestampSeconds, byte[] body) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
			mac.update((timestampSeconds + ".").getBytes(StandardCharsets.US_ASCII));
			return mac.doFinal(body);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

}

package io.github.moneymaker26754.agentforge.infrastructure.github;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 signing and constant-time verification for GitHub webhook
 * payloads. The signature format matches the X-Hub-Signature-256 header:
 * "sha256=" followed by the lowercase hex digest.
 */
public final class GitHubWebhookVerifier {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    private GitHubWebhookVerifier() {}

    public static String sign(byte[] rawBody, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return SIGNATURE_PREFIX + HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable on this JVM", exception);
        }
    }

    public static boolean verify(byte[] rawBody, String signatureHeader, String secret) {
        if (rawBody == null || signatureHeader == null || secret == null || secret.isEmpty()) {
            return false;
        }
        String header = signatureHeader.trim();
        if (!header.startsWith(SIGNATURE_PREFIX) || header.length() <= SIGNATURE_PREFIX.length()) {
            return false;
        }
        String expected = sign(rawBody, secret);
        return MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}

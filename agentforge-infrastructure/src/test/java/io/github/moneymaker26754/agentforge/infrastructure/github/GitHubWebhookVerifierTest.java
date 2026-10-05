package io.github.moneymaker26754.agentforge.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class GitHubWebhookVerifierTest {
    private static final byte[] BODY = "{\"action\":\"completed\"}".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = "webhook-secret";

    @Test
    void signProducesSha256PrefixedLowercaseHex() throws Exception {
        String signature = GitHubWebhookVerifier.sign(BODY, SECRET);

        assertThat(signature).startsWith("sha256=");
        String hex = signature.substring("sha256=".length());
        assertThat(hex).hasSize(64).matches("[0-9a-f]+");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(BODY));
        assertThat(hex).isEqualTo(expected);
    }

    @Test
    void verifyAcceptsSignatureProducedWithSameSecret() {
        String signature = GitHubWebhookVerifier.sign(BODY, SECRET);

        assertThat(GitHubWebhookVerifier.verify(BODY, signature, SECRET)).isTrue();
    }

    @Test
    void verifyRejectsTamperedBody() {
        String signature = GitHubWebhookVerifier.sign(BODY, SECRET);
        byte[] tampered = "{\"action\":\"completed\"}x".getBytes(StandardCharsets.UTF_8);

        assertThat(GitHubWebhookVerifier.verify(tampered, signature, SECRET)).isFalse();
    }

    @Test
    void verifyRejectsWrongSecret() {
        String signature = GitHubWebhookVerifier.sign(BODY, SECRET);

        assertThat(GitHubWebhookVerifier.verify(BODY, signature, "other-secret")).isFalse();
    }

    @Test
    void verifyRejectsMissingEmptyOrMalformedHeaders() {
        assertThat(GitHubWebhookVerifier.verify(BODY, null, SECRET)).isFalse();
        assertThat(GitHubWebhookVerifier.verify(BODY, "", SECRET)).isFalse();
        assertThat(GitHubWebhookVerifier.verify(BODY, "not-a-signature", SECRET)).isFalse();
        assertThat(GitHubWebhookVerifier.verify(BODY, "sha256=", SECRET)).isFalse();
        assertThat(GitHubWebhookVerifier.verify(BODY, "sha256=zz", SECRET)).isFalse();
    }

    @Test
    void verifyRejectsNullOrEmptySecret() {
        String signature = GitHubWebhookVerifier.sign(BODY, SECRET);

        assertThat(GitHubWebhookVerifier.verify(BODY, signature, null)).isFalse();
        assertThat(GitHubWebhookVerifier.verify(BODY, signature, "")).isFalse();
    }
}

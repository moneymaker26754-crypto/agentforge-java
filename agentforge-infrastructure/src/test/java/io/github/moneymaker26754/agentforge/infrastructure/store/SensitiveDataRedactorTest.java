package io.github.moneymaker26754.agentforge.infrastructure.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SensitiveDataRedactorTest {

    @Test
    void redactsSensitiveJsonFieldsAndBearerTokens() {
        var redactor = new SensitiveDataRedactor(new ObjectMapper());

        String redacted = redactor.redact("{\"api_key\":\"secret\",\"nested\":{\"Authorization\":\"Bearer abc\"},\"text\":\"token Bearer xyz\"}");

        assertThat(redacted).doesNotContain("secret", "abc", "xyz");
        assertThat(redacted).contains("[REDACTED]");
    }
}


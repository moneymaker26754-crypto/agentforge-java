package io.github.moneymaker26754.agentforge.infrastructure.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class SensitiveDataRedactor {
    private static final Set<String> SENSITIVE = Set.of(
            "authorization", "api_key", "apikey", "token", "access_token", "secret", "password");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+={0,2}");
    private final ObjectMapper mapper;

    public SensitiveDataRedactor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String redact(String value) {
        if (value == null) return "";
        try {
            JsonNode node = mapper.readTree(value);
            redactNode(node);
            return mapper.writeValueAsString(node);
        } catch (Exception ignored) {
            return BEARER.matcher(value).replaceAll("Bearer [REDACTED]");
        }
    }

    private void redactNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            var fields = object.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (isSensitive(field)) {
                    object.put(field, "[REDACTED]");
                } else {
                    JsonNode child = object.get(field);
                    if (child != null && child.isTextual()) {
                        object.put(field, BEARER.matcher(child.asText()).replaceAll("Bearer [REDACTED]"));
                    } else if (child != null) {
                        redactNode(child);
                    }
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(this::redactNode);
        }
    }

    private boolean isSensitive(String field) {
        String normalized = field.toLowerCase(Locale.ROOT);
        return SENSITIVE.contains(normalized) || normalized.endsWith("_token") || normalized.endsWith("_secret");
    }
}


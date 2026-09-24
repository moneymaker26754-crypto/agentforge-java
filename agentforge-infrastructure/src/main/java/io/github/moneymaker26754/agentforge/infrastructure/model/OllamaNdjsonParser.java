package io.github.moneymaker26754.agentforge.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.ModelDelta;
import io.github.moneymaker26754.agentforge.core.ModelDeltaSink;
import io.github.moneymaker26754.agentforge.core.ModelResponse;
import io.github.moneymaker26754.agentforge.core.Usage;
import java.util.Objects;

public final class OllamaNdjsonParser {
    private final ObjectMapper mapper;
    private final ModelDeltaSink sink;
    private final StringBuilder content = new StringBuilder();
    private final ToolCallAccumulator toolCalls = new ToolCallAccumulator();
    private long inputTokens;
    private long outputTokens;
    private String finishReason = "unknown";

    public OllamaNdjsonParser(ObjectMapper mapper, ModelDeltaSink sink) {
        this.mapper = Objects.requireNonNull(mapper);
        this.sink = Objects.requireNonNull(sink);
    }

    public void accept(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        try {
            JsonNode root = mapper.readTree(line);
            JsonNode message = root.path("message");
            String text = message.path("content").asText("");
            String reasoning = message.path("thinking").asText("");
            if (!text.isEmpty()) {
                content.append(text);
            }
            if (!text.isEmpty() || !reasoning.isEmpty()) {
                sink.accept(new ModelDelta(text, reasoning));
            }
            JsonNode streamedCalls = message.path("tool_calls");
            if (streamedCalls.isArray()) {
                int fallbackIndex = 0;
                for (JsonNode call : streamedCalls) {
                    JsonNode function = call.path("function");
                    int index = function.has("index") ? function.path("index").asInt() : fallbackIndex;
                    JsonNode arguments = function.path("arguments");
                    toolCalls.append(index, nullableText(call, "id"), nullableText(function, "name"),
                            arguments.isMissingNode() ? null : mapper.writeValueAsString(arguments), true);
                    fallbackIndex++;
                }
            }
            if (root.path("done").asBoolean(false)) {
                finishReason = root.path("done_reason").asText("stop");
            }
            inputTokens = root.path("prompt_eval_count").asLong(inputTokens);
            outputTokens = root.path("eval_count").asLong(outputTokens);
        } catch (Exception exception) {
            throw new ModelProtocolException("Invalid Ollama NDJSON frame", exception);
        }
    }

    public ModelResponse finish() {
        return new ModelResponse(content.toString(), toolCalls.finish("ollama-"),
                new Usage(inputTokens, outputTokens, 0), finishReason);
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}


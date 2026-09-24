package io.github.moneymaker26754.agentforge.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.core.ModelDelta;
import io.github.moneymaker26754.agentforge.core.ModelDeltaSink;
import io.github.moneymaker26754.agentforge.core.ModelResponse;
import io.github.moneymaker26754.agentforge.core.Usage;
import java.util.Objects;

public final class DeepSeekSseParser {
    private final ObjectMapper mapper;
    private final ModelDeltaSink sink;
    private final double inputPricePerThousand;
    private final double outputPricePerThousand;
    private final StringBuilder content = new StringBuilder();
    private final ToolCallAccumulator toolCalls = new ToolCallAccumulator();
    private long inputTokens;
    private long outputTokens;
    private String finishReason = "unknown";

    public DeepSeekSseParser(ObjectMapper mapper, ModelDeltaSink sink, double inputPricePerThousand,
            double outputPricePerThousand) {
        this.mapper = Objects.requireNonNull(mapper);
        this.sink = Objects.requireNonNull(sink);
        this.inputPricePerThousand = inputPricePerThousand;
        this.outputPricePerThousand = outputPricePerThousand;
    }

    public void accept(String line) {
        if (line == null || line.isBlank() || line.startsWith(":")) {
            return;
        }
        String data = line.startsWith("data:") ? line.substring(5).trim() : line.trim();
        if (data.equals("[DONE]")) {
            return;
        }
        try {
            JsonNode root = mapper.readTree(data);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode choice = choices.get(0);
                JsonNode delta = choice.path("delta");
                String text = delta.path("content").asText("");
                String reasoning = delta.path("reasoning_content").asText("");
                if (!text.isEmpty()) {
                    content.append(text);
                }
                if (!text.isEmpty() || !reasoning.isEmpty()) {
                    sink.accept(new ModelDelta(text, reasoning));
                }
                JsonNode streamedCalls = delta.path("tool_calls");
                if (streamedCalls.isArray()) {
                    for (JsonNode call : streamedCalls) {
                        JsonNode function = call.path("function");
                        toolCalls.append(call.path("index").asInt(0), nullableText(call, "id"),
                                nullableText(function, "name"), nullableText(function, "arguments"), false);
                    }
                }
                if (!choice.path("finish_reason").isNull() && !choice.path("finish_reason").isMissingNode()) {
                    finishReason = choice.path("finish_reason").asText("unknown");
                }
            }
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode() && !usage.isNull()) {
                inputTokens = usage.path("prompt_tokens").asLong(0);
                outputTokens = usage.path("completion_tokens").asLong(0);
            }
        } catch (Exception exception) {
            throw new ModelProtocolException("Invalid DeepSeek SSE frame", exception);
        }
    }

    public ModelResponse finish() {
        double cost = (inputTokens * inputPricePerThousand + outputTokens * outputPricePerThousand) / 1000.0;
        return new ModelResponse(content.toString(), toolCalls.finish("deepseek-"),
                new Usage(inputTokens, outputTokens, cost), finishReason);
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}


package io.github.moneymaker26754.agentforge.core;

import java.util.List;

public record ModelResponse(String content, List<ToolCall> toolCalls, Usage usage, String finishReason) {
    public ModelResponse {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? Usage.zero() : usage;
        finishReason = finishReason == null ? "unknown" : finishReason;
    }

    public static ModelResponse finalAnswer(String content, Usage usage) {
        return new ModelResponse(content, List.of(), usage, "stop");
    }

    public static ModelResponse toolCalls(List<ToolCall> calls, Usage usage) {
        return new ModelResponse("", calls, usage, "tool_calls");
    }
}


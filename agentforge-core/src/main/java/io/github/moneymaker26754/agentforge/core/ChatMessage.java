package io.github.moneymaker26754.agentforge.core;

import java.util.List;
import java.util.Objects;

public record ChatMessage(ChatRole role, String content, String toolCallId, List<ToolCall> toolCalls) {
    public ChatMessage {
        Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM, content, null, List.of());
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER, content, null, List.of());
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ChatRole.ASSISTANT, content, null, List.of());
    }

    public static ChatMessage assistantWithTools(String content, List<ToolCall> calls) {
        return new ChatMessage(ChatRole.ASSISTANT, content, null, calls);
    }

    public static ChatMessage tool(String callId, String content) {
        return new ChatMessage(ChatRole.TOOL, content, callId, List.of());
    }
}


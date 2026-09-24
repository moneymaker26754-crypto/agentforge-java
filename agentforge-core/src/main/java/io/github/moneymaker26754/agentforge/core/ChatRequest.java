package io.github.moneymaker26754.agentforge.core;

import java.util.List;

public record ChatRequest(String model, List<ChatMessage> messages, List<ToolDescriptor> tools) {
    public ChatRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
    }
}


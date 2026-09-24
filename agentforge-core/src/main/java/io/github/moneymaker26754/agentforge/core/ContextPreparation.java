package io.github.moneymaker26754.agentforge.core;

import java.util.List;

public record ContextPreparation(List<ChatMessage> messages, boolean compressed, int tokensBefore, int tokensAfter) {
    public ContextPreparation {
        messages = List.copyOf(messages);
    }
}


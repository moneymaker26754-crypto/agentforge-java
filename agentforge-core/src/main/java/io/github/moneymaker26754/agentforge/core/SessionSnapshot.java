package io.github.moneymaker26754.agentforge.core;

import java.time.Instant;
import java.util.List;

public record SessionSnapshot(SessionId sessionId, long sequence, RunStatus status, List<ChatMessage> messages,
        Usage usage, Instant createdAt) {
    public SessionSnapshot {
        messages = List.copyOf(messages);
    }
}


package io.github.moneymaker26754.agentforge.core;

import java.time.Instant;

public record SessionEvent(SessionId sessionId, long sequence, EventType type, Instant occurredAt,
        String payload, String previousHash, String hash) {
    public static SessionEvent of(SessionId sessionId, long sequence, EventType type, Instant occurredAt,
            String payload) {
        return new SessionEvent(sessionId, sequence, type, occurredAt, payload == null ? "" : payload, "", "");
    }
}


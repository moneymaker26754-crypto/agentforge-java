package io.github.moneymaker26754.agentforge.core;

import java.util.UUID;

public record SessionId(String value) {
    public SessionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("session id must not be blank");
        }
    }

    public static SessionId random() {
        return new SessionId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}


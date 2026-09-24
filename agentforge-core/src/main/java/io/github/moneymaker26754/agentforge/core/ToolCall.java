package io.github.moneymaker26754.agentforge.core;

public record ToolCall(String id, int index, String name, String argumentsJson) {
    public String fingerprint() {
        return name + "\n" + argumentsJson;
    }
}


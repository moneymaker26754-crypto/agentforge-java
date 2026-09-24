package io.github.moneymaker26754.agentforge.core;

public record ToolDefinition(String name, String description, String jsonSchema, RiskLevel riskLevel,
        boolean idempotent, RawToolExecutor executor) {
    @FunctionalInterface
    public interface RawToolExecutor {
        ToolResult execute(String argumentsJson, ExecutionContext context);
    }
}


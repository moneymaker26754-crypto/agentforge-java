package io.github.moneymaker26754.agentforge.core;

public record ToolInvocation(ToolCall call, ToolDefinition definition, ExecutionContext context) {}


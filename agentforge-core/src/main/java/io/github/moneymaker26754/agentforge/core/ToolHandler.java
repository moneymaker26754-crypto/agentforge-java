package io.github.moneymaker26754.agentforge.core;

public interface ToolHandler<A extends Record> {
    ToolResult execute(A arguments, ExecutionContext context);
}


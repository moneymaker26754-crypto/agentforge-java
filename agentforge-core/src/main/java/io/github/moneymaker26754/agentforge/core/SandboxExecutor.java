package io.github.moneymaker26754.agentforge.core;

public interface SandboxExecutor {
    ExecutionResult execute(CommandSpec command, ExecutionContext context);
}


package io.github.moneymaker26754.agentforge.infrastructure.sandbox;

import io.github.moneymaker26754.agentforge.core.*;

public final class RoutingSandboxExecutor implements SandboxExecutor {
    private final SandboxExecutor docker;
    private final SandboxExecutor local;

    public RoutingSandboxExecutor(SandboxExecutor docker, SandboxExecutor local) {
        this.docker = docker;
        this.local = local;
    }

    @Override public ExecutionResult execute(CommandSpec command, ExecutionContext context) {
        return context.sandboxMode() == SandboxMode.DOCKER
                ? docker.execute(command, context)
                : local.execute(command, context);
    }
}


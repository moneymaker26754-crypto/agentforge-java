package io.github.moneymaker26754.agentforge.infrastructure.sandbox;

import io.github.moneymaker26754.agentforge.core.*;
import java.util.Set;

public final class DockerSandboxExecutor implements SandboxExecutor {
    private final DockerCommandFactory commandFactory;
    private final LocalSandboxExecutor processExecutor = new LocalSandboxExecutor(Set.of("docker", "docker.exe"));

    public DockerSandboxExecutor(DockerCommandFactory commandFactory) {
        this.commandFactory = commandFactory;
    }

    @Override public ExecutionResult execute(CommandSpec command, ExecutionContext context) {
        var dockerCommand = new CommandSpec(commandFactory.create(context.workspace(), command),
                command.timeout(), command.maxOutputBytes());
        return processExecutor.execute(dockerCommand, context);
    }
}


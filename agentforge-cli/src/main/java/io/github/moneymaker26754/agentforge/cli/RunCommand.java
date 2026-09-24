package io.github.moneymaker26754.agentforge.cli;

import io.github.moneymaker26754.agentforge.core.*;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;

@Command(name = "run", description = "Run an agent task against a repository", mixinStandardHelpOptions = true)
public final class RunCommand implements Callable<Integer> {
    private final AgentOperations operations;
    @Spec private Model.CommandSpec spec;
    @Option(names = "--repo", required = true) private Path repository;
    @Option(names = "--task", required = true) private String task;
    @Option(names = "--provider", defaultValue = "deepseek") private String provider;
    @Option(names = "--sandbox", defaultValue = "docker") private String sandbox;

    public RunCommand(AgentOperations operations) { this.operations = operations; }

    @Override public Integer call() {
        var result = operations.run(repository, task,
                ProviderId.valueOf(provider.toUpperCase(Locale.ROOT)),
                SandboxMode.valueOf(sandbox.toUpperCase(Locale.ROOT)));
        spec.commandLine().getOut().printf("session=%s%nstatus=%s%nanswer=%s%n", result.sessionId(),
                result.status(), result.answer());
        return result.status() == RunStatus.COMPLETED ? 0 : 1;
    }
}


package io.github.moneymaker26754.agentforge.cli;

import io.github.moneymaker26754.agentforge.core.SessionId;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;

@Command(name = "report", description = "Render a session report")
public final class ReportCommand implements Callable<Integer> {
    private final AgentOperations operations;
    @Parameters(index = "0") private String sessionId;
    @Option(names = "--format", defaultValue = "markdown") private String format;
    @Spec private Model.CommandSpec spec;
    public ReportCommand(AgentOperations operations) { this.operations = operations; }
    @Override public Integer call() {
        spec.commandLine().getOut().println(operations.report(new SessionId(sessionId), format));
        return 0;
    }
}


package io.github.moneymaker26754.agentforge.cli;

import io.github.moneymaker26754.agentforge.core.*;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;

@Command(name = "resume", description = "Resume or reconcile a checkpointed session")
public final class ResumeCommand implements Callable<Integer> {
    private final AgentOperations operations;
    @Parameters(index = "0") private String sessionId;
    @Spec private Model.CommandSpec spec;
    public ResumeCommand(AgentOperations operations) { this.operations = operations; }
    @Override public Integer call() {
        var result = operations.resume(new SessionId(sessionId));
        spec.commandLine().getOut().printf("session=%s%nstatus=%s%nreason=%s%n", result.sessionId(), result.status(), result.terminationReason());
        return result.status() == RunStatus.COMPLETED ? 0 : result.status() == RunStatus.UNCERTAIN ? 3 : 1;
    }
}


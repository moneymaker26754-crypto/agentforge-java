package io.github.moneymaker26754.agentforge.cli;

import io.github.moneymaker26754.agentforge.core.SessionId;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(name = "session", description = "Inspect checkpointed sessions",
        subcommands = {SessionCommand.ListCommand.class, SessionCommand.ShowCommand.class})
public final class SessionCommand implements Runnable {
    @Override public void run() { CommandLine.usage(this, System.out); }

    @Command(name = "list", description = "List session identifiers")
    public static final class ListCommand implements Callable<Integer> {
        private final AgentOperations operations;
        @Spec private Model.CommandSpec spec;
        public ListCommand(AgentOperations operations) { this.operations = operations; }
        @Override public Integer call() {
            operations.sessions().forEach(id -> spec.commandLine().getOut().println(id));
            return 0;
        }
    }

    @Command(name = "show", description = "Show the verified event stream")
    public static final class ShowCommand implements Callable<Integer> {
        private final AgentOperations operations;
        @Parameters(index = "0") private String sessionId;
        @Spec private Model.CommandSpec spec;
        public ShowCommand(AgentOperations operations) { this.operations = operations; }
        @Override public Integer call() {
            operations.session(new SessionId(sessionId)).forEach(event -> spec.commandLine().getOut().printf(
                    "%d %s %s %s%n", event.sequence(), event.occurredAt(), event.type(), event.payload()));
            return 0;
        }
    }
}

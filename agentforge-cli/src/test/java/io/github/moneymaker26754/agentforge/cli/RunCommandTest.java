package io.github.moneymaker26754.agentforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.moneymaker26754.agentforge.core.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class RunCommandTest {
    @Test
    void mapsCliOptionsToAgentOperationAndPrintsRunIdentity() {
        var operations = new RecordingOperations();
        var output = new StringWriter();
        var line = new CommandLine(new RunCommand(operations)).setOut(new PrintWriter(output));

        int exit = line.execute("--repo", ".", "--task", "fix it", "--provider", "ollama", "--sandbox", "local");

        assertThat(exit).isZero();
        assertThat(operations.provider).isEqualTo(ProviderId.OLLAMA);
        assertThat(operations.sandbox).isEqualTo(SandboxMode.LOCAL);
        assertThat(operations.task).isEqualTo("fix it");
        assertThat(output.toString()).contains("session=test-session", "status=COMPLETED", "answer=done");
    }

    private static final class RecordingOperations implements AgentOperations {
        String task;
        ProviderId provider;
        SandboxMode sandbox;

        @Override public RunResult run(Path repository, String task, ProviderId provider, SandboxMode sandbox) {
            this.task = task;
            this.provider = provider;
            this.sandbox = sandbox;
            return new RunResult(new SessionId("test-session"), RunStatus.COMPLETED, "done", Usage.zero(),
                    TerminationReason.FINAL_ANSWER, 1);
        }
        @Override public RunResult resume(SessionId id) { throw new UnsupportedOperationException(); }
        @Override public List<SessionId> sessions() { return List.of(); }
        @Override public List<SessionEvent> session(SessionId id) { return List.of(); }
        @Override public String report(SessionId id, String format) { return ""; }
    }
}


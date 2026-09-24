package io.github.moneymaker26754.agentforge.cli;

import io.github.moneymaker26754.agentforge.core.*;
import java.nio.file.Path;
import java.util.List;

public interface AgentOperations {
    RunResult run(Path repository, String task, ProviderId provider, SandboxMode sandbox);
    RunResult resume(SessionId id);
    List<SessionId> sessions();
    List<SessionEvent> session(SessionId id);
    String report(SessionId id, String format);
}


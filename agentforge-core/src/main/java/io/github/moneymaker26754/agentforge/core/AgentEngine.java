package io.github.moneymaker26754.agentforge.core;

public interface AgentEngine {
    RunResult run(RunRequest request);

    RunResult resume(SessionId sessionId);
}


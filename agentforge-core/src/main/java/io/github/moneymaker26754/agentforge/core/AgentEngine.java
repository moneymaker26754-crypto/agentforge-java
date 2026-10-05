package io.github.moneymaker26754.agentforge.core;

public interface AgentEngine {
    RunResult run(RunRequest request);

    RunResult resume(SessionId sessionId);

    /** Resolves a session suspended in {@link RunStatus#WAITING_APPROVAL} and continues the loop. */
    RunResult resume(SessionId sessionId, ApprovalDecision decision);
}


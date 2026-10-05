package io.github.moneymaker26754.agentforge.core;

public enum ApprovalDecision {
    APPROVE_ONCE,
    REJECT,
    /** Suspend the session; the decision arrives later through {@code AgentEngine.resume(id, decision)}. */
    WAITING
}


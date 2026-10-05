package io.github.moneymaker26754.agentforge.server.domain;

/** Lifecycle of a CI diagnosis task, persisted in {@code agent_tasks}. */
public enum TaskStatus {
    CREATED,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
    BUDGET_EXHAUSTED,
    UNCERTAIN
}

package io.github.moneymaker26754.agentforge.server.domain;

/** A persisted CI diagnosis task. The idempotency key is repository + commitSha + workflowRunId. */
public record AgentTask(String id, String repository, String commitSha, Long workflowRunId, TaskStatus status,
        String sessionId, String prompt, String createdAt, String updatedAt) {
    public static AgentTask create(String id, String repository, String commitSha, Long workflowRunId, String prompt,
            String now) {
        return new AgentTask(id, repository, commitSha, workflowRunId, TaskStatus.CREATED, null, prompt, now, now);
    }

    public AgentTask withStatus(TaskStatus newStatus, String updatedAt) {
        return new AgentTask(id, repository, commitSha, workflowRunId, newStatus, sessionId, prompt, createdAt,
                updatedAt);
    }

    public AgentTask withSession(String newSessionId, TaskStatus newStatus, String updatedAt) {
        return new AgentTask(id, repository, commitSha, workflowRunId, newStatus, newSessionId, prompt, createdAt,
                updatedAt);
    }
}

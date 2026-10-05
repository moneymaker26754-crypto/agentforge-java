package io.github.moneymaker26754.agentforge.server.store;

import io.github.moneymaker26754.agentforge.server.domain.AgentTask;
import io.github.moneymaker26754.agentforge.server.domain.PendingApprovalRow;
import java.util.List;
import java.util.Optional;

/** Persistence for CI diagnosis tasks and pending human approvals. */
public interface AgentTaskStore {
    /** Inserts a task; returns the existing task when the idempotency key already exists. */
    AgentTask insertIdempotent(AgentTask task);

    Optional<AgentTask> findByKey(String repository, String commitSha, Long workflowRunId);

    Optional<AgentTask> findById(String taskId);

    Optional<AgentTask> findBySessionId(String sessionId);

    List<AgentTask> list(int limit);

    void update(AgentTask task);

    void saveApproval(PendingApprovalRow row);

    Optional<PendingApprovalRow> findApproval(String approvalId);

    void decideApproval(String approvalId, String decision, String decidedAt);

    Optional<PendingApprovalRow> findPendingApprovalBySession(String sessionId);

    List<PendingApprovalRow> listPendingApprovals(int limit);
}

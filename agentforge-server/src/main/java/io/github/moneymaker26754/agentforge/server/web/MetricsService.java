package io.github.moneymaker26754.agentforge.server.web;

import io.github.moneymaker26754.agentforge.core.CheckpointStore;
import io.github.moneymaker26754.agentforge.core.SessionId;
import io.github.moneymaker26754.agentforge.core.SessionMetrics;
import io.github.moneymaker26754.agentforge.server.domain.TaskStatus;
import io.github.moneymaker26754.agentforge.server.store.AgentTaskStore;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregated control-plane metrics: task throughput by status plus per-session runtime accounting
 * (tool calls, failures, token and cost totals) recomputed from the audit event stream.
 */
public final class MetricsService {
    private final AgentTaskStore taskStore;
    private final CheckpointStore checkpointStore;

    public MetricsService(AgentTaskStore taskStore, CheckpointStore checkpointStore) {
        this.taskStore = taskStore;
        this.checkpointStore = checkpointStore;
    }

    public Map<String, Object> summary(int taskLimit) {
        var tasks = taskStore.list(taskLimit);
        Map<String, Long> tasksByStatus = new LinkedHashMap<>();
        for (TaskStatus status : TaskStatus.values()) {
            tasksByStatus.put(status.name(), 0L);
        }
        long sessions = 0;
        long toolCalls = 0;
        long toolFailures = 0;
        long inputTokens = 0;
        long outputTokens = 0;
        double costCny = 0;
        for (var task : tasks) {
            tasksByStatus.merge(task.status().name(), 1L, Long::sum);
            if (task.sessionId() == null) {
                continue;
            }
            SessionId sessionId = new SessionId(task.sessionId());
            SessionMetrics metrics = SessionMetrics.of(checkpointStore.replay(sessionId),
                    checkpointStore.latestSnapshot(sessionId));
            sessions++;
            toolCalls += metrics.toolCalls();
            toolFailures += metrics.toolFailures();
            inputTokens += metrics.inputTokens();
            outputTokens += metrics.outputTokens();
            costCny += metrics.costCny();
        }
        long approvalsPending = taskStore.listPendingApprovals(10_000).size();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tasksTotal", tasks.size());
        summary.put("tasksByStatus", tasksByStatus);
        summary.put("sessions", sessions);
        summary.put("toolCalls", toolCalls);
        summary.put("toolFailures", toolFailures);
        summary.put("avgToolCallsPerSession", sessions == 0 ? 0 : (double) toolCalls / sessions);
        summary.put("inputTokens", inputTokens);
        summary.put("outputTokens", outputTokens);
        summary.put("costCny", costCny);
        summary.put("approvalsPending", approvalsPending);
        return summary;
    }
}

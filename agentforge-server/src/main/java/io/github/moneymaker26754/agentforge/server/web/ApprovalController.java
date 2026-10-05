package io.github.moneymaker26754.agentforge.server.web;

import io.github.moneymaker26754.agentforge.core.ApprovalDecision;
import io.github.moneymaker26754.agentforge.core.SessionId;
import io.github.moneymaker26754.agentforge.server.domain.PendingApprovalRow;
import io.github.moneymaker26754.agentforge.server.domain.TaskStatus;
import io.github.moneymaker26754.agentforge.server.runtime.ServerAgentRuntime;
import io.github.moneymaker26754.agentforge.server.store.AgentTaskStore;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Human approval API: list pending approvals and resolve one. Resolving resumes the suspended
 * session with the decision, exactly like the interactive CLI prompt but through an HTTP caller.
 */
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {
    private final AgentTaskStore taskStore;
    private final ServerAgentRuntime runtime;
    private final Clock clock;

    public ApprovalController(AgentTaskStore taskStore, ServerAgentRuntime runtime, Clock clock) {
        this.taskStore = taskStore;
        this.runtime = runtime;
        this.clock = clock;
    }

    public record DecisionRequest(String decision) {}

    @GetMapping
    public List<PendingApprovalRow> list(@RequestParam(defaultValue = "50") int limit) {
        return taskStore.listPendingApprovals(limit);
    }

    @PostMapping("/{approvalId}")
    public ResponseEntity<Map<String, Object>> decide(@PathVariable String approvalId,
            @RequestBody DecisionRequest body) {
        PendingApprovalRow row = taskStore.findApproval(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("unknown approval: " + approvalId));
        if (row.decision() != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "approval already decided", "decision", row.decision()));
        }
        ApprovalDecision decision;
        try {
            decision = ApprovalDecision.valueOf(body.decision().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("decision must be APPROVE_ONCE or REJECT");
        }
        if (decision == ApprovalDecision.WAITING) {
            throw new IllegalArgumentException("decision must be APPROVE_ONCE or REJECT");
        }
        taskStore.decideApproval(approvalId, decision.name(), clock.instant().toString());
        var result = runtime.resume(new SessionId(row.sessionId()), decision);
        taskStore.findBySessionId(row.sessionId()).ifPresent(task -> taskStore.update(
                task.withStatus(map(result.status()), clock.instant().toString())));
        return ResponseEntity.ok(Map.of("approvalId", approvalId, "sessionId", row.sessionId(),
                "status", result.status().name()));
    }

    private TaskStatus map(io.github.moneymaker26754.agentforge.core.RunStatus status) {
        return switch (status) {
            case COMPLETED -> TaskStatus.COMPLETED;
            case WAITING_APPROVAL -> TaskStatus.WAITING_APPROVAL;
            case BUDGET_EXHAUSTED -> TaskStatus.BUDGET_EXHAUSTED;
            case UNCERTAIN -> TaskStatus.UNCERTAIN;
            default -> TaskStatus.FAILED;
        };
    }
}

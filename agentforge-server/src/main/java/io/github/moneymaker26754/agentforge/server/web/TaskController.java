package io.github.moneymaker26754.agentforge.server.web;

import io.github.moneymaker26754.agentforge.server.domain.AgentTask;
import io.github.moneymaker26754.agentforge.server.domain.CiTaskRequest;
import io.github.moneymaker26754.agentforge.server.domain.TaskStatus;
import io.github.moneymaker26754.agentforge.server.runtime.TaskExecutionCoordinator;
import io.github.moneymaker26754.agentforge.server.store.AgentTaskStore;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Task lifecycle API: create a CI diagnosis task, list tasks, read a task's status and session. */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {
    private final AgentTaskStore taskStore;
    private final TaskExecutionCoordinator coordinator;
    private final Clock clock;

    public TaskController(AgentTaskStore taskStore, TaskExecutionCoordinator coordinator, Clock clock) {
        this.taskStore = taskStore;
        this.coordinator = coordinator;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CiTaskRequest body) {
        if (body.workflowRunId() > 0) {
            var existing = taskStore.findByKey(body.repository(), body.commitSha(), body.workflowRunId());
            if (existing.isPresent()) {
                return ResponseEntity.ok(Map.of("taskId", existing.get().id(), "created", false));
            }
        }
        AgentTask task = taskStore.insertIdempotent(AgentTask.create(UUID.randomUUID().toString(),
                body.repository(), body.commitSha(), body.workflowRunId() > 0 ? body.workflowRunId() : null,
                body.task(), clock.instant().toString()));
        coordinator.submit(task);
        return ResponseEntity.accepted().body(Map.of("taskId", task.id(), "created", true));
    }

    @GetMapping
    public List<AgentTask> list(@RequestParam(defaultValue = "50") int limit) {
        return taskStore.list(limit);
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<AgentTask> get(@PathVariable String taskId) {
        return taskStore.findById(taskId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

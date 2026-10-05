package io.github.moneymaker26754.agentforge.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moneymaker26754.agentforge.infrastructure.github.CiContextAssembler;
import io.github.moneymaker26754.agentforge.infrastructure.github.CiFailureContext;
import io.github.moneymaker26754.agentforge.infrastructure.github.CiWebhookEvent;
import io.github.moneymaker26754.agentforge.infrastructure.github.GitHubApiClient;
import io.github.moneymaker26754.agentforge.infrastructure.github.GitHubWebhookIgnoreException;
import io.github.moneymaker26754.agentforge.infrastructure.github.GitHubWebhookParser;
import io.github.moneymaker26754.agentforge.infrastructure.github.GitHubWebhookVerifier;
import io.github.moneymaker26754.agentforge.server.config.ServerProperties;
import io.github.moneymaker26754.agentforge.server.domain.AgentTask;
import io.github.moneymaker26754.agentforge.server.runtime.TaskExecutionCoordinator;
import io.github.moneymaker26754.agentforge.server.store.AgentTaskStore;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GitHub Actions webhook endpoint.
 *
 * <p>The raw body is verified with the HMAC-SHA256 signature first, then parsed. Failed
 * {@code workflow_run} / {@code workflow_job} events become idempotent diagnosis tasks
 * (repository + commit SHA + workflow run id); events that do not represent a failure are
 * acknowledged and ignored so GitHub stops retrying, and a replayed webhook returns the original
 * task instead of starting a second session.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookController.class);
    private static final int CONTEXT_TOKEN_BUDGET = 4_000;

    private final ServerProperties properties;
    private final GitHubWebhookParser parser;
    private final GitHubApiClient github;
    private final CiContextAssembler assembler;
    private final AgentTaskStore taskStore;
    private final TaskExecutionCoordinator coordinator;
    private final Clock clock;

    public WebhookController(ServerProperties properties, ObjectMapper mapper, GitHubApiClient github,
            CiContextAssembler assembler, AgentTaskStore taskStore, TaskExecutionCoordinator coordinator,
            Clock clock) {
        this.properties = properties;
        this.parser = new GitHubWebhookParser(mapper);
        this.github = github;
        this.assembler = assembler;
        this.taskStore = taskStore;
        this.coordinator = coordinator;
        this.clock = clock;
    }

    @PostMapping("/github")
    public ResponseEntity<Map<String, Object>> github(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        if (!properties.webhookSecret().isBlank()) {
            String signature = request.getHeader("X-Hub-Signature-256");
            if (!GitHubWebhookVerifier.verify(body, signature, properties.webhookSecret())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid signature"));
            }
        }
        CiWebhookEvent event;
        try {
            event = parser.parse(new String(body, StandardCharsets.UTF_8));
        } catch (GitHubWebhookIgnoreException ignored) {
            return ResponseEntity.ok(Map.of("acknowledged", true, "ignored", true));
        }
        var existing = taskStore.findByKey(event.repository(), event.commitSha(), event.workflowRunId());
        if (existing.isPresent()) {
            return ResponseEntity.ok(Map.of("taskId", existing.get().id(), "created", false));
        }
        AgentTask task = taskStore.insertIdempotent(AgentTask.create(UUID.randomUUID().toString(),
                event.repository(), event.commitSha(), event.workflowRunId(), promptFor(event), now()));
        coordinator.submit(task);
        return ResponseEntity.accepted().body(Map.of("taskId", task.id(), "created", true));
    }

    private String promptFor(CiWebhookEvent event) {
        CiFailureContext context = new CiFailureContext(event.repository(), event.commitSha(),
                event.workflowRunId(), event.workflowName(), event.failedJobNames(), "", "", "", List.of(), "",
                List.of(), "");
        try {
            var failedJobs = github.getFailedJobs(event.repository(), event.workflowRunId());
            StringBuilder logs = new StringBuilder();
            for (var job : failedJobs) {
                logs.append("== job ").append(job.name()).append(" ==\n")
                        .append(github.getJobLogs(event.repository(), job.id(), 8_000)).append('\n');
            }
            var changed = event.commitSha() == null || event.commitSha().isBlank()
                    ? List.<String>of()
                    : github.getCommit(event.repository(), event.commitSha()).changedFiles();
            String diff = "";
            if (event.commitSha() != null && !event.commitSha().isBlank()) {
                try {
                    diff = github.getDiff(event.repository(), event.commitSha() + "^", event.commitSha());
                } catch (IOException ignored) {
                    // A diff is evidence, not a requirement; keep the brief without it.
                }
            }
            context = new CiFailureContext(event.repository(), event.commitSha(), event.workflowRunId(),
                    event.workflowName(), event.failedJobNames(), logs.toString(), "",
                    CiContextAssembler.extractStackTrace(logs.toString()), changed, diff, List.of(), "");
        } catch (IOException exception) {
            LOGGER.warn("cannot enrich webhook context for {}: {}", event.repository(), exception.getMessage());
        }
        return assembler.assembleBrief(context, CONTEXT_TOKEN_BUDGET);
    }

    private String now() {
        return clock.instant().toString();
    }
}

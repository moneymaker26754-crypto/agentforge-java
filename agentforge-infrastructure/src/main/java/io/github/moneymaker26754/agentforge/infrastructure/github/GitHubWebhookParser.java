package io.github.moneymaker26754.agentforge.infrastructure.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;

/**
 * Parses GitHub webhook payloads into a normalized {@link CiWebhookEvent}.
 * Only "completed" workflow_run/workflow_job events with conclusion "failure"
 * are accepted; everything else throws {@link GitHubWebhookIgnoreException}.
 */
public final class GitHubWebhookParser {
    private final ObjectMapper mapper;

    public GitHubWebhookParser(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    public CiWebhookEvent parse(String payloadJson) {
        JsonNode root;
        try {
            root = mapper.readTree(payloadJson);
        } catch (JsonProcessingException exception) {
            throw new GitHubWebhookIgnoreException("webhook payload is not valid JSON", exception);
        }
        if (root == null || !root.isObject()) {
            throw new GitHubWebhookIgnoreException("webhook payload is not a JSON object");
        }
        JsonNode workflowRun = root.get("workflow_run");
        if (workflowRun != null && workflowRun.isObject()) {
            return fromWorkflowRun(root, textOrNull(root, "action"), workflowRun);
        }
        JsonNode workflowJob = root.get("workflow_job");
        if (workflowJob != null && workflowJob.isObject()) {
            return fromWorkflowJob(root, textOrNull(root, "action"), workflowJob);
        }
        throw new GitHubWebhookIgnoreException("unrecognized GitHub webhook event payload");
    }

    private static CiWebhookEvent fromWorkflowRun(JsonNode root, String action, JsonNode run) {
        if (!"completed".equals(action)) {
            throw new GitHubWebhookIgnoreException("workflow_run action is not 'completed': " + action);
        }
        String conclusion = textOrNull(run, "conclusion");
        if (!"failure".equals(conclusion)) {
            throw new GitHubWebhookIgnoreException("workflow_run conclusion is not 'failure': " + conclusion);
        }
        // The workflow_run payload nests the repository under the run; older payload shapes keep it
        // at the top level, so fall back before failing.
        String repository = textOrNull(run.path("repository"), "full_name");
        if (repository == null) {
            repository = textOrNull(root.path("repository"), "full_name");
        }
        return new CiWebhookEvent(
                repository,
                textOrNull(run, "head_sha"),
                run.path("id").asLong(),
                textOrNull(run, "name"),
                List.of());
    }

    private static CiWebhookEvent fromWorkflowJob(JsonNode root, String action, JsonNode job) {
        if (!"completed".equals(action)) {
            throw new GitHubWebhookIgnoreException("workflow_job action is not 'completed': " + action);
        }
        String conclusion = textOrNull(job, "conclusion");
        if (!"failure".equals(conclusion)) {
            throw new GitHubWebhookIgnoreException("workflow_job conclusion is not 'failure': " + conclusion);
        }
        String workflowName = textOrNull(job, "workflow_name");
        if (workflowName == null) {
            workflowName = textOrNull(job, "name");
        }
        String jobName = textOrNull(job, "name");
        return new CiWebhookEvent(
                textOrNull(root.path("repository"), "full_name"),
                textOrNull(job, "head_sha"),
                job.path("run_id").asLong(),
                workflowName,
                jobName == null ? List.of() : List.of(jobName));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.isMissingNode() ? null : value.asText();
    }
}

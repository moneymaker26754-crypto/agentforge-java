package io.github.moneymaker26754.agentforge.infrastructure.github;

import java.util.List;

/**
 * Normalized view of a GitHub webhook payload that describes a failed CI run.
 * commitSha may be null for workflow_job events that do not carry a head_sha.
 * failedJobNames is empty for workflow_run events (job names are fetched via
 * the API afterwards) and contains the failed job name for workflow_job
 * events.
 */
public record CiWebhookEvent(
        String repository,
        String commitSha,
        long workflowRunId,
        String workflowName,
        List<String> failedJobNames) {}

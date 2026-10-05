package io.github.moneymaker26754.agentforge.infrastructure.github;

/**
 * A GitHub Actions workflow run, as returned by the workflows/runs and runs
 * endpoints. Timestamps and conclusion/status are kept as raw API strings
 * because they may be absent or null.
 */
public record WorkflowRun(
        long id,
        String name,
        String conclusion,
        String status,
        String headSha,
        String createdAt) {}

package io.github.moneymaker26754.agentforge.server.domain;

/** Request body for creating a CI diagnosis task through the control-plane API. */
public record CiTaskRequest(String repository, String commitSha, long workflowRunId, String task, String sandboxMode) {
    public CiTaskRequest {
        if (repository == null || repository.isBlank()) {
            throw new IllegalArgumentException("repository must not be blank");
        }
        commitSha = commitSha == null ? "" : commitSha;
        task = task == null || task.isBlank()
                ? "Diagnose and fix the reported CI failure, then verify with targeted tests."
                : task;
    }
}
